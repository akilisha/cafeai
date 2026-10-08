package io.cafeai.identity;

import io.cafeai.core.identity.Identity;
import io.helidon.common.Errors;
import io.helidon.security.jwt.Jwt;
import io.helidon.security.jwt.SignedJwt;
import io.helidon.security.jwt.jwk.Jwk;
import io.helidon.security.jwt.jwk.JwkKeys;
import io.helidon.json.JsonNumber;
import io.helidon.json.JsonObject;
import io.helidon.json.JsonValue;
import io.helidon.json.JsonValueType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates a JWT access token against an issuer and builds the {@link Identity} it proves.
 *
 * <p>The signature is checked by Helidon's JWT library against the issuer's published key. The
 * checks around it are explicit, in this order: an asymmetric algorithm from the allowed list
 * (never {@code none}, never a shared-secret {@code HS*} algorithm, which a public key set
 * could be abused to forge), optionally the RFC 9068 {@code at+jwt} type, a known key, a valid
 * signature, then {@code iss}, {@code sub}, {@code exp}, {@code nbf} and {@code aud}.
 */
final class TokenValidator {

    /** Why a token was refused. The description is what the caller is told. */
    enum Failure {
        MALFORMED("The token is malformed"),
        ALGORITHM("The token's signing algorithm is not accepted"),
        TYPE("The token is not an access token"),
        UNKNOWN_KEY("The token is signed with an unknown key"),
        SIGNATURE("The token's signature is invalid"),
        ISSUER("The token is from a different issuer"),
        SUBJECT("The token has no subject"),
        EXPIRED("The token has expired"),
        NOT_YET_VALID("The token is not valid yet"),
        AUDIENCE("The token is not intended for this service");

        final String description;

        Failure(String description) { this.description = description; }
    }

    /** The outcome: an identity, or why there isn't one. */
    record Result(Identity identity, Failure failure) {
        static Result ok(Identity identity)   { return new Result(identity, null); }
        static Result refused(Failure reason) { return new Result(null, reason); }
        boolean valid()                       { return identity != null; }
    }

    static final Set<String> DEFAULT_ALGORITHMS = Set.of(
            "RS256", "RS384", "RS512", "PS256", "PS384", "PS512", "ES256", "ES384", "ES512");

    private final Issuer issuer;
    private final Set<String> audiences;
    private final Set<String> algorithms;
    private final Duration clockSkew;
    private final boolean requireAccessTokenType;
    private final Clock clock;

    TokenValidator(Issuer issuer, Set<String> audiences, Set<String> algorithms,
                   Duration clockSkew, boolean requireAccessTokenType, Clock clock) {
        this.issuer = issuer;
        this.audiences = Set.copyOf(audiences);
        this.algorithms = Set.copyOf(algorithms);
        this.clockSkew = clockSkew;
        this.requireAccessTokenType = requireAccessTokenType;
        this.clock = clock;
    }

    /**
     * Validates {@code token}.
     *
     * @throws IdentityException if the issuer's keys can't be read at all (not the caller's fault)
     */
    Result validate(String token) {
        SignedJwt signed;
        Jwt jwt;
        try {
            if (token.chars().filter(c -> c == '.').count() != 2) return Result.refused(Failure.MALFORMED);
            signed = SignedJwt.parseToken(token);
            jwt = signed.getJwt();
        } catch (RuntimeException e) {
            return Result.refused(Failure.MALFORMED);
        }

        String alg = jwt.algorithm().orElse(null);
        if (alg == null || !algorithms.contains(alg)) return Result.refused(Failure.ALGORITHM);

        if (requireAccessTokenType) {
            String typ = jwt.type().orElse("");
            if (!typ.equalsIgnoreCase("at+jwt") && !typ.equalsIgnoreCase("application/at+jwt")) {
                return Result.refused(Failure.TYPE);
            }
        }

        Optional<Jwk> key = issuer.key(jwt.keyId().orElse(null));
        if (key.isEmpty()) return Result.refused(Failure.UNKNOWN_KEY);
        if (!alg.equals(key.get().algorithm())) return Result.refused(Failure.SIGNATURE);

        Errors errors;
        try {
            errors = signed.verifySignature(JwkKeys.builder().addKey(key.get()).build(), key.get());
        } catch (RuntimeException e) {
            return Result.refused(Failure.SIGNATURE);
        }
        if (!errors.isValid()) return Result.refused(Failure.SIGNATURE);

        if (!issuer.id().equals(jwt.issuer().orElse(null))) return Result.refused(Failure.ISSUER);

        String subject = jwt.subject().orElse(null);
        if (subject == null || subject.isBlank()) return Result.refused(Failure.SUBJECT);

        Instant now = clock.instant();
        Instant exp = jwt.expirationTime().orElse(null);
        if (exp == null || !now.isBefore(exp.plus(clockSkew))) return Result.refused(Failure.EXPIRED);
        Optional<Instant> nbf = jwt.notBefore();
        if (nbf.isPresent() && now.isBefore(nbf.get().minus(clockSkew))) return Result.refused(Failure.NOT_YET_VALID);

        List<String> aud = jwt.audience().orElse(List.of());
        if (aud.stream().noneMatch(audiences::contains)) return Result.refused(Failure.AUDIENCE);

        return Result.ok(identity(issuer.id(), subject, exp, jwt.payloadClaimsJson()));
    }

    /**
     * The identity a token's claims describe, for a token already validated: an ID token kept
     * server-side after sign-in is turned back into an identity this way on later requests.
     */
    static Identity identityOf(String token, Instant expiresAt) {
        Jwt jwt = SignedJwt.parseToken(token).getJwt();
        return identity(jwt.issuer().orElseThrow(), jwt.subject().orElseThrow(), expiresAt, jwt.payloadClaimsJson());
    }

    private static Identity identity(String iss, String sub, Instant exp, Map<String, JsonValue> payload) {
        Map<String, Object> claims = new LinkedHashMap<>();
        payload.forEach((name, value) -> {
            Object v = toJava(value);
            if (v != null) claims.put(name, v);
        });
        return Identity.builder(iss, sub)
                .expiresAt(exp)
                .name(text(payload.get("name")).or(() -> text(payload.get("preferred_username"))).orElse(null))
                .scopes(scopes(payload.get("scope")))
                .groups(values(payload.get("groups")))
                .roles(values(payload.get("roles")))
                .entitlements(values(payload.get("entitlements")))
                .claims(claims)
                .build();
    }

    /** {@code scope} is one space-separated string (RFC 9068, RFC 8693). */
    private static Set<String> scopes(JsonValue value) {
        Set<String> out = new LinkedHashSet<>();
        text(value).ifPresent(s -> {
            for (String part : s.trim().split("\s+")) if (!part.isEmpty()) out.add(part);
        });
        return out;
    }

    /**
     * {@code groups}, {@code roles} and {@code entitlements} (RFC 9068 2.2.3.1): a list of
     * strings, or of SCIM-style objects with a {@code value}. A single string is accepted too.
     */
    private static Set<String> values(JsonValue value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null) return out;
        if (value.type() == JsonValueType.STRING) {
            out.add(value.asString().value());
        } else if (value.type() == JsonValueType.ARRAY) {
            for (JsonValue item : value.asArray().values()) {
                if (item.type() == JsonValueType.STRING) out.add(item.asString().value());
                else if (item.type() == JsonValueType.OBJECT) item.asObject().value("value").flatMap(TokenValidator::text).ifPresent(out::add);
            }
        }
        return out;
    }

    private static Optional<String> text(JsonValue value) {
        return value != null && value.type() == JsonValueType.STRING
                ? Optional.of(value.asString().value()) : Optional.empty();
    }

    private static Object toJava(JsonValue value) {
        if (value == null) return null;
        return switch (value.type()) {
            case STRING -> value.asString().value();
            case NUMBER -> {
                JsonNumber n = value.asNumber();
                yield n.bigDecimalValue().stripTrailingZeros().scale() <= 0
                        ? (Object) n.longValue() : (Object) n.doubleValue();
            }
            case BOOLEAN -> value.asBoolean().value();
            case ARRAY -> {
                List<Object> list = new ArrayList<>();
                for (JsonValue item : value.asArray().values()) list.add(toJava(item));
                yield Collections.unmodifiableList(list);
            }
            case OBJECT -> {
                JsonObject object = value.asObject();
                Map<String, Object> map = new LinkedHashMap<>();
                for (String key : object.keysAsStrings()) map.put(key, toJava(object.value(key).orElse(null)));
                yield Collections.unmodifiableMap(map);
            }
            case NULL, UNKNOWN -> null;
        };
    }
}
