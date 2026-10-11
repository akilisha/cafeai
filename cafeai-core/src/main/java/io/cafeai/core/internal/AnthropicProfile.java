package io.cafeai.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cafeai.core.ai.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A Claude Console sign-in made with Anthropic's {@code ant auth login}: the profile Anthropic's
 * CLI, SDKs and Claude Code share. Read in the same places and the same order as Anthropic's
 * SDKs:
 *
 * <ul>
 *   <li>the directory: {@code $ANTHROPIC_CONFIG_DIR}, else {@code %APPDATA%\Anthropic} on Windows,
 *       else {@code $XDG_CONFIG_HOME/anthropic}, else {@code ~/.config/anthropic};</li>
 *   <li>the profile: {@code $ANTHROPIC_PROFILE}, else the name in {@code active_config}, else
 *       {@code default};</li>
 *   <li>{@code configs/<profile>.json} (the {@code authentication} block, {@code base_url}) and
 *       {@code credentials/<profile>.json} (the access token, its expiry in epoch seconds, the
 *       refresh token).</li>
 * </ul>
 *
 * <p>{@link #credentials()} renews the access token in-process the way Anthropic's SDKs do (the
 * {@code refresh_token} grant at {@code <base_url>/v1/oauth/token}, with the profile's
 * {@code client_id} and the {@code oauth-2025-04-20} beta) and writes it back, so {@code ant} and
 * Claude Code see the new token. Public only so {@code io.cafeai.core.login} can reach it.
 */
public final class AnthropicProfile {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProfile.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    /** Renewed this long before it expires, as {@code ant auth print-credentials} does. */
    static final Duration RENEW_BEFORE = Duration.ofSeconds(120);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    /** One renewer per credentials file, so two providers in one app never race each other. */
    private static final Map<Path, Renewer> renewers = new ConcurrentHashMap<>();

    private final String name;
    private final Path dir;
    private final Path config;
    private final Path credentials;
    private final String type;
    private final String clientId;
    private final String baseUrl;
    private final String workspaceId;

    private AnthropicProfile(String name, Path dir, Path config, Path credentials, String type,
                             String clientId, String baseUrl, String workspaceId) {
        this.name = name;
        this.dir = dir;
        this.config = config;
        this.credentials = credentials;
        this.type = type;
        this.clientId = clientId;
        this.baseUrl = baseUrl;
        this.workspaceId = workspaceId;
    }

    /** Anthropic's configuration directory, or {@code null} when no home directory is known. */
    public static Path dir(UnaryOperator<String> env) {
        String explicit = env.apply("ANTHROPIC_CONFIG_DIR");
        if (explicit != null) return Path.of(explicit);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            String appData = env.apply("APPDATA");
            return appData == null || appData.isBlank() ? null : Path.of(appData, "Anthropic");
        }
        String xdg = env.apply("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "anthropic");
        String home = System.getProperty("user.home");
        return home == null || home.isBlank() ? null : Path.of(home, ".config", "anthropic");
    }

    /**
     * The active profile, if it exists. A profile named in {@code $ANTHROPIC_PROFILE} that doesn't
     * exist is an error, not "no profile", as in Anthropic's SDKs.
     */
    public static Optional<AnthropicProfile> active(UnaryOperator<String> env) {
        Path dir = dir(env);
        if (dir == null) return Optional.empty();
        String named = env.apply("ANTHROPIC_PROFILE");
        String name = named != null ? named : activeName(dir);
        if (!NAME.matcher(name).matches()) {
            throw new IllegalStateException("Invalid Anthropic profile name '" + name + "'");
        }
        Path config = dir.resolve("configs").resolve(name + ".json");
        if (!Files.isRegularFile(config)) {
            if (named != null) {
                throw new IllegalStateException("ANTHROPIC_PROFILE names the profile '" + name
                        + "', but " + config + " doesn't exist. Run `ant auth login --profile " + name + "`.");
            }
            return Optional.empty();
        }
        JsonNode root = readJson(config);
        JsonNode auth = root.path("authentication");
        if (!auth.isObject() || !auth.path("type").isTextual()) {
            throw new IllegalStateException("The Anthropic profile " + config + " has no authentication type");
        }
        String credentialsPath = auth.path("credentials_path").asText("");
        Path credentials = credentialsPath.isEmpty()
                ? dir.resolve("credentials").resolve(name + ".json") : Path.of(credentialsPath);
        String base = root.path("base_url").asText("");
        if (base.isEmpty()) base = Optional.ofNullable(env.apply("ANTHROPIC_BASE_URL")).filter(s -> !s.isBlank()).orElse("");
        return Optional.of(new AnthropicProfile(name, dir, config, credentials, auth.path("type").asText(),
                emptyToNull(auth.path("client_id").asText("")), emptyToNull(base),
                emptyToNull(root.path("workspace_id").asText(""))));
    }

    private static String activeName(Path dir) {
        try {
            Path pointer = dir.resolve("active_config");
            if (Files.isRegularFile(pointer)) {
                String name = Files.readString(pointer).strip();
                if (!name.isEmpty()) return name;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + dir.resolve("active_config"), e);
        }
        return "default";
    }

    public String name() { return name; }

    /** The configuration directory this profile is in. */
    public Path dir() { return dir; }

    /** {@code user_oauth} for an {@code ant auth login} sign-in, {@code oidc_federation} for WIF. */
    public String type() { return type; }

    /** Whether this is a person's sign-in ({@code ant auth login}), the kind CafeAI uses. */
    public boolean isSignIn() { return "user_oauth".equals(type); }

    /** The profile's API host, or {@code null} for Anthropic's own. */
    public String baseUrl() { return baseUrl; }

    /** The credentials file, or empty when signed out. */
    public Optional<JsonNode> stored() {
        return Files.isRegularFile(credentials) ? Optional.of(readJson(credentials)) : Optional.empty();
    }

    /** Signs out the way {@code ant auth logout} does: removes the credentials file, keeps the profile. */
    public boolean signOut() {
        try {
            renewers.remove(credentials);
            return Files.deleteIfExists(credentials);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not remove " + credentials, e);
        }
    }

    /** The profile's access token for model calls, renewed when it nears expiry. */
    public Credentials credentials() {
        if (!isSignIn()) {
            throw new IllegalStateException("The Anthropic profile '" + name + "' is a " + type
                    + " profile, not an `ant auth login` sign-in. For Workload Identity Federation, use"
                    + " AnthropicFederation from cafeai-identity.");
        }
        Renewer renewer = renewers.compute(credentials, (path, existing) ->
                existing != null && existing.matches(this) ? existing : new Renewer(this, Clock.systemUTC()));
        return renewer;
    }

    /** {@link #credentials()} with a clock, for tests. */
    Credentials credentials(Clock clock) {
        return new Renewer(this, clock);
    }

    @Override
    public String toString() {
        return "AnthropicProfile(" + name + ", " + config + ")";
    }

    private static final class Renewer implements Credentials {
        private final AnthropicProfile profile;
        private final Clock clock;

        Renewer(AnthropicProfile profile, Clock clock) {
            this.profile = profile;
            this.clock = clock;
        }

        boolean matches(AnthropicProfile other) {
            return java.util.Objects.equals(profile.clientId, other.clientId)
                    && java.util.Objects.equals(profile.baseUrl, other.baseUrl);
        }

        @Override
        public synchronized String token() {
            ObjectNode stored = read();
            if (fresh(stored)) return stored.path("access_token").asText();
            String refreshToken = stored.path("refresh_token").asText("");
            if (profile.clientId == null || refreshToken.isEmpty()) {
                throw expired("it can't be renewed (no refresh token or client id)");
            }
            try {
                return renew(stored, refreshToken);
            } catch (RefusedException e) {
                // Another program (ant, Claude Code) may have renewed it first and used up the
                // refresh token: take theirs if so.
                ObjectNode again = read();
                if (fresh(again)) return again.path("access_token").asText();
                throw expired("renewing it was refused: " + e.getMessage());
            }
        }

        private boolean fresh(ObjectNode stored) {
            String token = stored.path("access_token").asText("");
            if (token.isEmpty()) return false;
            JsonNode expires = stored.get("expires_at");
            if (expires == null || !expires.canConvertToLong()) return true;   // lifetime unknown
            return clock.instant().isBefore(Instant.ofEpochSecond(expires.asLong()).minus(RENEW_BEFORE));
        }

        private ObjectNode read() {
            if (!Files.isRegularFile(profile.credentials)) {
                throw new IllegalStateException("Not signed in to Claude (Anthropic profile '" + profile.name
                        + "' has no credentials). Run `cafeai login claude`.");
            }
            JsonNode node = readJson(profile.credentials);
            if (!(node instanceof ObjectNode object)) {
                throw new IllegalStateException("The Anthropic credentials file " + profile.credentials + " is not a JSON object");
            }
            String type = object.path("type").asText("");
            if (!type.isEmpty() && !type.equals("oauth_token") && !type.equals("access_token")) {
                throw new IllegalStateException("The Anthropic credentials file " + profile.credentials
                        + " has an unknown type '" + type + "'");
            }
            return object;
        }

        private String renew(ObjectNode stored, String refreshToken) {
            String base = profile.baseUrl != null ? profile.baseUrl : DEFAULT_BASE_URL;
            URI endpoint = URI.create(trimSlash(base) + "/v1/oauth/token");
            requireSecure(endpoint);
            String body = JSON.createObjectNode()
                    .put("grant_type", "refresh_token")
                    .put("refresh_token", refreshToken)
                    .put("client_id", profile.clientId).toString();
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("anthropic-beta", "oauth-2025-04-20")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response;
            try {
                response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while renewing the Claude sign-in", e);
            } catch (IOException e) {
                throw new IllegalStateException("Could not reach " + endpoint + " to renew the Claude sign-in: "
                        + e.getMessage(), e);
            }
            JsonNode answer;
            try {
                answer = JSON.readTree(response.body());
            } catch (IOException e) {
                answer = null;
            }
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                throw new RefusedException("HTTP " + response.statusCode() + errorOf(answer));
            }
            if (response.statusCode() != 200 || answer == null || answer.path("access_token").asText("").isEmpty()) {
                throw new IllegalStateException("Renewing the Claude sign-in failed at " + endpoint + ": HTTP "
                        + response.statusCode() + errorOf(answer));
            }
            long expiresIn = answer.path("expires_in").asLong(3600);
            stored.put("version", stored.path("version").asText("1.0"));
            stored.put("type", "oauth_token");
            stored.put("access_token", answer.path("access_token").asText());
            stored.put("expires_at", clock.instant().plusSeconds(expiresIn).getEpochSecond());
            if (!answer.path("refresh_token").asText("").isEmpty()) {
                stored.put("refresh_token", answer.path("refresh_token").asText());
            }
            if (!answer.path("scope").asText("").isEmpty()) stored.put("scope", answer.path("scope").asText());
            write(profile.credentials, stored);
            log.debug("Renewed the Claude sign-in for Anthropic profile '{}'", profile.name);
            return answer.path("access_token").asText();
        }

        private IllegalStateException expired(String why) {
            return new IllegalStateException("The Claude sign-in (Anthropic profile '" + profile.name
                    + "') has expired and " + why + ". Sign in again: `cafeai login claude`.");
        }

        @Override public String toString() { return "AnthropicProfile.credentials(" + profile.name + ")"; }
    }

    private static final class RefusedException extends RuntimeException {
        RefusedException(String message) { super(message); }
    }

    private static String errorOf(JsonNode answer) {
        if (answer == null) return "";
        JsonNode error = answer.path("error");
        if (error.isTextual()) return " (" + error.asText() + ")";
        if (error.path("message").isTextual()) return " (" + error.path("message").asText() + ")";
        return "";
    }

    /** As Anthropic's SDKs: a credential only goes over https, or plain http to this machine. */
    private static void requireSecure(URI endpoint) {
        String scheme = endpoint.getScheme();
        String host = endpoint.getHost() == null ? "" : endpoint.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("[::1]");
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw new IllegalStateException("Refusing to send the Claude refresh token to " + endpoint + " (not https)");
        }
    }

    private static JsonNode readJson(Path file) {
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Written the way Anthropic's SDKs write it: a temp file beside it, owner-only, moved into place. */
    private static void write(Path file, ObjectNode content) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                if (parent.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                }
                Files.writeString(temp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(content),
                        StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
