package io.cafeai.identity;

import io.helidon.json.JsonObject;
import io.helidon.json.JsonValue;
import io.helidon.json.JsonValueType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The issuer's word on an access token (RFC 7662), asked after the local check:
 * <ul>
 *   <li>a JWT that passed locally is still refused if the issuer says it is no longer active,
 *       so a revoked token, or one whose holder was disabled, stops working at once instead of
 *       at its expiry;</li>
 *   <li>a token that isn't a JWT at all (an opaque token) is decided by the issuer alone: an
 *       active answer must name this issuer (when it names one), this service among its
 *       audiences, a subject, and an expiry still ahead; the caller is built from it.</li>
 * </ul>
 * Any other local failure (a bad signature, an expired token, another audience) is final: the
 * issuer isn't asked.
 *
 * <p>Answers are cached for a short time ({@link #DEFAULT_CACHE}, configurable), and never past
 * the token's expiry, so the issuer isn't asked on every request; that time is how late a
 * revocation can be noticed. Tokens are cached by their SHA-256, never as themselves.
 */
final class Introspection {

    static final Duration DEFAULT_CACHE = Duration.ofSeconds(30);
    private static final int CACHE_LIMIT = 10_000;

    private record Answer(TokenValidator.Result result, Instant until) { }

    private final String issuerId;
    private final Set<String> audiences;
    private final TokenEndpoint endpoint;
    private final Duration cacheFor;
    private final Duration clockSkew;
    private final Clock clock;
    private final Map<String, Answer> cache = new ConcurrentHashMap<>();

    Introspection(Issuer issuer, Set<String> audiences, TokenEndpoint endpoint, Duration cacheFor,
                  Duration clockSkew, Clock clock) {
        this.issuerId = issuer.id();
        this.audiences = Set.copyOf(audiences);
        this.endpoint = endpoint;
        this.cacheFor = cacheFor;
        this.clockSkew = clockSkew;
        this.clock = clock;
    }

    /**
     * The final outcome for {@code token}, given the local check's.
     *
     * @throws IdentityException if the issuer can't be asked (not the caller's fault)
     */
    TokenValidator.Result check(String token, TokenValidator.Result local) {
        boolean opaque = !local.valid() && local.failure() == TokenValidator.Failure.MALFORMED;
        if (!local.valid() && !opaque) return local;

        Instant now = clock.instant();
        String key = fingerprint(token);
        Answer cached = cache.get(key);
        if (cached != null && now.isBefore(cached.until())) return cached.result();

        JsonObject answer = endpoint.introspect(token);
        TokenValidator.Result result = opaque ? fromAnswer(answer, now)
                : active(answer) ? local : TokenValidator.Result.refused(TokenValidator.Failure.INACTIVE);

        Instant until = now.plus(cacheFor);
        if (result.valid() && result.identity().expiresAt() != null && result.identity().expiresAt().isBefore(until)) {
            until = result.identity().expiresAt();
        }
        if (cache.size() >= CACHE_LIMIT) cache.values().removeIf(a -> !now.isBefore(a.until()));
        if (cache.size() < CACHE_LIMIT) cache.put(key, new Answer(result, until));
        return result;
    }

    /** An opaque token: the issuer's answer is all there is. */
    private TokenValidator.Result fromAnswer(JsonObject answer, Instant now) {
        if (!active(answer)) return TokenValidator.Result.refused(TokenValidator.Failure.INACTIVE);
        Optional<String> iss = answer.stringValue("iss");
        if (iss.isPresent() && !iss.get().equals(issuerId)) {
            return TokenValidator.Result.refused(TokenValidator.Failure.ISSUER);
        }
        String sub = answer.stringValue("sub").orElse(null);
        if (sub == null || sub.isBlank()) return TokenValidator.Result.refused(TokenValidator.Failure.SUBJECT);
        Optional<Long> exp = answer.longValue("exp");
        if (exp.isEmpty() || !now.isBefore(Instant.ofEpochSecond(exp.get()).plus(clockSkew))) {
            return TokenValidator.Result.refused(TokenValidator.Failure.EXPIRED);
        }
        if (audienceOf(answer).stream().noneMatch(audiences::contains)) {
            return TokenValidator.Result.refused(TokenValidator.Failure.AUDIENCE);
        }
        return TokenValidator.Result.ok(TokenValidator.identityOf(issuerId, sub, Instant.ofEpochSecond(exp.get()), answer));
    }

    private static boolean active(JsonObject answer) {
        return answer.value("active").filter(v -> v.type() == JsonValueType.BOOLEAN)
                .map(v -> v.asBoolean().value()).orElse(false);
    }

    /** {@code aud}: one string or a list of them (RFC 7662 2.2). */
    private static List<String> audienceOf(JsonObject answer) {
        JsonValue aud = answer.value("aud").orElse(null);
        if (aud == null) return List.of();
        if (aud.type() == JsonValueType.STRING) return List.of(aud.asString().value());
        if (aud.type() != JsonValueType.ARRAY) return List.of();
        return aud.asArray().values().stream()
                .filter(v -> v.type() == JsonValueType.STRING).map(v -> v.asString().value()).toList();
    }

    private static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
