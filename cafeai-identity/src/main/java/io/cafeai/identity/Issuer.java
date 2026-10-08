package io.cafeai.identity;

import io.helidon.security.jwt.jwk.Jwk;
import io.helidon.security.jwt.jwk.JwkKeys;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonParser;
import io.helidon.json.JsonValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * An OpenID Connect issuer that CafeAI trusts: its identifier, its endpoints, and the public
 * keys it signs tokens with.
 *
 * <pre>{@code
 *   var issuer = Issuer.discover("https://issuer.example.com/realms/acme");
 *   app.filter(Auth.bearer(issuer, "orders-api"));
 * }</pre>
 *
 * <p>{@link #discover(String)} reads the issuer's metadata (OpenID Connect Discovery) once, when
 * called, and fails then if the issuer can't be reached or doesn't identify itself as the
 * requested issuer. The signing keys (its JWK set) are fetched on first use and cached:
 * <ul>
 *   <li>a token signed with a key id the cache doesn't know triggers a refresh, which is how a
 *       rotated key is picked up; refreshes for unknown keys are at most one per
 *       {@linkplain #minRefreshInterval(Duration) minimum interval}, so forged key ids can't
 *       make CafeAI hammer the issuer;</li>
 *   <li>the cache is also refreshed once it is older than its {@linkplain #maxKeyAge(Duration)
 *       maximum age}, so a key the issuer withdrew stops being trusted;</li>
 *   <li>if a refresh fails, the keys already cached keep working.</li>
 * </ul>
 *
 * <p>The issuer must be HTTPS, except on the loopback interface, for local development.
 * Thread-safe.
 */
public final class Issuer {

    private static final Logger log = LoggerFactory.getLogger(Issuer.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final String id;
    private final URI jwksUri;
    private final JsonObject metadata;
    private final HttpClient http;
    private final Clock clock;

    private volatile Duration minRefreshInterval = Duration.ofSeconds(30);
    private volatile Duration maxKeyAge = Duration.ofHours(1);

    private final Object keysLock = new Object();
    private volatile JwkKeys keys;
    private volatile Instant keysFetchedAt;
    private volatile Instant lastUnknownKeyRefresh;

    private Issuer(String id, URI jwksUri, JsonObject metadata, HttpClient http, Clock clock) {
        this.id = id;
        this.jwksUri = jwksUri;
        this.metadata = metadata;
        this.http = http;
        this.clock = clock;
    }

    /**
     * Reads the issuer's OpenID Connect Discovery metadata from
     * {@code <issuer>/.well-known/openid-configuration}.
     *
     * @param issuer the issuer identifier, exactly as it appears in its tokens' {@code iss}
     * @throws IdentityException if the metadata can't be read, has no {@code jwks_uri}, or names
     *         a different issuer than the one requested
     */
    public static Issuer discover(String issuer) {
        return discover(issuer, Clock.systemUTC());
    }

    static Issuer discover(String issuer, Clock clock) {
        URI issuerUri = requireAllowedScheme(issuer, "issuer");
        HttpClient http = newHttpClient();
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        URI metadataUri = URI.create(base + "/.well-known/openid-configuration");

        JsonObject metadata = fetchJson(http, metadataUri, "issuer metadata");
        String claimed = string(metadata, "issuer")
                .orElseThrow(() -> new IdentityException("Issuer metadata at " + metadataUri + " has no 'issuer'"));
        // OpenID Connect Discovery 4.3: the metadata must name exactly the issuer requested.
        if (!claimed.equals(issuer)) {
            throw new IdentityException("Issuer metadata at " + metadataUri + " names issuer '" + claimed
                    + "', not '" + issuer + "'. Use the issuer identifier exactly as the issuer states it.");
        }
        URI jwks = requireAllowedScheme(string(metadata, "jwks_uri")
                .orElseThrow(() -> new IdentityException("Issuer metadata at " + metadataUri + " has no 'jwks_uri'")),
                "jwks_uri");
        log.debug("Discovered issuer {} (keys at {}, host {})", issuer, jwks, issuerUri.getHost());
        return new Issuer(issuer, jwks, metadata, http, clock);
    }

    /**
     * An issuer configured by hand, for issuers that don't publish Discovery metadata.
     *
     * @param issuer  the issuer identifier, exactly as it appears in its tokens' {@code iss}
     * @param jwksUri where the issuer publishes its signing keys
     */
    public static Issuer of(String issuer, String jwksUri) {
        requireAllowedScheme(issuer, "issuer");
        return new Issuer(issuer, requireAllowedScheme(jwksUri, "jwks_uri"),
                JsonObject.empty(), newHttpClient(), Clock.systemUTC());
    }

    /** The issuer identifier: the value its tokens carry in {@code iss}. */
    public String id() { return id; }

    /** Where the issuer publishes its signing keys. */
    public URI jwksUri() { return jwksUri; }

    /** An endpoint from the issuer's metadata, such as {@code token_endpoint}, or empty. */
    public Optional<URI> endpoint(String name) {
        return string(metadata, name).map(URI::create);
    }

    /**
     * The longest the key cache is trusted before it is fetched again (default one hour). Lower
     * it to stop trusting a withdrawn key sooner.
     */
    public Issuer maxKeyAge(Duration maxKeyAge) {
        this.maxKeyAge = positive(maxKeyAge, "maxKeyAge");
        return this;
    }

    /**
     * The shortest time between refreshes caused by an unknown key id (default 30 seconds).
     */
    public Issuer minRefreshInterval(Duration interval) {
        this.minRefreshInterval = positive(interval, "minRefreshInterval");
        return this;
    }

    // -- keys ------------------------------------------------------------------------------

    /**
     * The key with this id, refreshing the cache once if it isn't known (rate-limited).
     * A {@code null} key id is looked up as the issuer's only key, if it has exactly one.
     */
    Optional<Jwk> key(String keyId) {
        JwkKeys current = currentKeys();
        Optional<Jwk> found = lookup(current, keyId);
        if (found.isPresent()) return found;

        synchronized (keysLock) {
            // Another thread may have refreshed while this one waited.
            if (keys != current) {
                found = lookup(keys, keyId);
                if (found.isPresent()) return found;
            }
            Instant now = clock.instant();
            // Only refreshes caused by unknown key ids are rate-limited: a scheduled refresh
            // must not delay picking up a key the issuer has just rotated in.
            if (lastUnknownKeyRefresh != null && now.isBefore(lastUnknownKeyRefresh.plus(minRefreshInterval))) {
                return Optional.empty();
            }
            lastUnknownKeyRefresh = now;
            refresh(now);
            return lookup(keys, keyId);
        }
    }

    private JwkKeys currentKeys() {
        JwkKeys k = keys;
        Instant fetched = keysFetchedAt;
        if (k != null && clock.instant().isBefore(fetched.plus(maxKeyAge))) {
            return k;
        }
        synchronized (keysLock) {
            if (keys == null || !clock.instant().isBefore(keysFetchedAt.plus(maxKeyAge))) {
                refresh(clock.instant());
            }
            return keys;
        }
    }

    /** Fetches the key set; on failure keeps what was cached, or throws if nothing was. */
    private void refresh(Instant now) {
        try {
            JsonObject json = fetchJson(http, jwksUri, "signing keys");
            keys = JwkKeys.create(json);
            keysFetchedAt = now;
        } catch (RuntimeException e) {
            if (keys == null) {
                throw e instanceof IdentityException ie ? ie
                        : new IdentityException("Could not read signing keys from " + jwksUri, e);
            }
            // Keep the cached keys; try again after the minimum interval.
            log.warn("Could not refresh signing keys from {}; keeping the cached keys: {}", jwksUri, e.getMessage());
            keysFetchedAt = now.minus(maxKeyAge).plus(minRefreshInterval);
        }
    }

    private static Optional<Jwk> lookup(JwkKeys keys, String keyId) {
        if (keys == null) return Optional.empty();
        if (keyId != null) return keys.forKeyId(keyId);
        return keys.keys().size() == 1 ? Optional.of(keys.keys().get(0)) : Optional.empty();
    }

    // -- http ------------------------------------------------------------------------------

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private static JsonObject fetchJson(HttpClient http, URI uri, String what) {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(uri)
                            .timeout(HTTP_TIMEOUT)
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityException("Interrupted while reading " + what + " from " + uri, e);
        } catch (Exception e) {
            throw new IdentityException("Could not read " + what + " from " + uri + ": " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new IdentityException("Could not read " + what + " from " + uri + ": HTTP " + response.statusCode());
        }
        try {
            var value = JsonParser.create(response.body()).readJsonValue();
            if (value.type() == JsonValueType.OBJECT) return value.asObject();
        } catch (RuntimeException e) {
            throw new IdentityException("The " + what + " at " + uri + " are not a JSON object", e);
        }
        throw new IdentityException("The " + what + " at " + uri + " are not a JSON object");
    }

    private static URI requireAllowedScheme(String value, String what) {
        Objects.requireNonNull(value, what);
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IdentityException(what + " is not a valid URI: " + value, e);
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) return uri;
        if ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost())) return uri;
        throw new IdentityException(what + " must use https (plain http is allowed only on the loopback "
                + "interface, for development): " + value);
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        if (host.equalsIgnoreCase("localhost")) return true;
        try {
            // Only literal addresses: a name is never resolved here.
            if (!host.matches("[0-9.]+") && !host.contains(":")) return false;
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static Optional<String> string(JsonObject json, String name) {
        return json.stringValue(name);
    }

    private static Duration positive(Duration d, String what) {
        Objects.requireNonNull(d, what);
        if (d.isNegative() || d.isZero()) throw new IllegalArgumentException(what + " must be positive");
        return d;
    }

    @Override
    public String toString() {
        return "Issuer[" + id + "]";
    }
}
