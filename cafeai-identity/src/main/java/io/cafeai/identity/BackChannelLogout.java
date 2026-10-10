package io.cafeai.identity;

import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.json.JsonValue;
import io.helidon.json.JsonValueType;
import io.helidon.security.jwt.Jwt;
import io.helidon.webserver.http.HttpRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenID Connect Back-Channel Logout 1.0: the issuer tells the app, server to server, that a
 * session at the issuer ended (the person signed out elsewhere, or an administrator ended it),
 * and the app's sessions that came from it end too.
 *
 * <p>The issuer POSTs a {@code logout_token}: a JWT it signed, for this client, carrying the
 * back-channel logout event and the issuer's session id ({@code sid}), the person
 * ({@code sub}), or both. It is checked like any token from the issuer (signature, algorithm,
 * {@code iss}), then: {@code aud} names this client, the event is there, there is no
 * {@code nonce} (so an ID token can't pass for one), {@code iat} is recent, {@code exp} (if
 * any) is ahead, and its {@code jti} hasn't been seen before.
 *
 * <p>CafeAI doesn't reach into the session store; a session is ended the next time it is used.
 * With a {@code sid}, the session signed in under that issuer session ends; with only a
 * {@code sub}, every session of that person signed in before the logout ends. Logouts are
 * remembered for {@link #KEPT}, in this process.
 */
final class BackChannelLogout {

    private static final Logger log = LoggerFactory.getLogger(BackChannelLogout.class);

    static final String EVENT = "http://schemas.openid.net/event/backchannel-logout";
    /** How long a logout is remembered, for sessions that come back after it. */
    static final Duration KEPT = Duration.ofHours(24);
    /** A logout token older than this is refused: it was sent for something long over. */
    private static final Duration MAX_AGE = Duration.ofMinutes(10);
    private static final Duration SKEW = Duration.ofSeconds(60);

    private final Issuer issuer;
    private final String clientId;
    private final Clock clock;
    private final Map<String, Instant> endedSids = new ConcurrentHashMap<>();
    private final Map<String, Instant> endedSubjects = new ConcurrentHashMap<>();
    private final Map<String, Instant> seenJtis = new ConcurrentHashMap<>();
    private volatile Instant lastForget;

    BackChannelLogout(Issuer issuer, String clientId, Clock clock) {
        this.issuer = issuer;
        this.clientId = clientId;
        this.clock = clock;
    }

    /** Serves {@code POST path} at the Helidon level: the issuer brings no cookie, CSRF token or body parser. */
    void install(HttpRouting.Builder routing, String path) {
        routing.post(path, (req, res) -> {
            String body = req.content().as(String.class);
            String token = form(body).get("logout_token");
            res.header(HeaderNames.CACHE_CONTROL, "no-store");
            Optional<String> refused = token == null ? Optional.of("no logout_token") : accept(token);
            if (refused.isPresent()) {
                log.warn("Refused a back-channel logout: {}", refused.get());
                res.status(Status.BAD_REQUEST_400).header(HeaderNames.CONTENT_TYPE, "application/json")
                        .send("{\"error\":\"invalid_request\"}");
            } else {
                res.status(Status.OK_200).send();
            }
        });
    }

    /** Validates a logout token and records the logout; empty when accepted, else why not. */
    Optional<String> accept(String token) {
        TokenValidator.Signed signed;
        try {
            signed = TokenValidator.signedByIssuer(token, issuer, TokenValidator.DEFAULT_ALGORITHMS);
        } catch (IdentityException e) {
            return Optional.of("the issuer's keys can't be read: " + e.getMessage());
        }
        if (signed.failure() != null) return Optional.of(signed.failure().description);
        Jwt jwt = signed.jwt();
        Map<String, JsonValue> claims = jwt.payloadClaimsJson();

        String typ = jwt.type().orElse("logout+jwt");
        if (!typ.equalsIgnoreCase("logout+jwt") && !typ.equalsIgnoreCase("application/logout+jwt")
                && !typ.equalsIgnoreCase("JWT")) {
            return Optional.of("type " + typ);
        }
        if (!jwt.audience().orElse(List.of()).contains(clientId)) return Optional.of("not for this client");
        JsonValue events = claims.get("events");
        boolean event = events != null && events.type() == JsonValueType.OBJECT
                && events.asObject().value(EVENT).filter(v -> v.type() == JsonValueType.OBJECT).isPresent();
        if (!event) return Optional.of("no back-channel logout event");
        if (claims.containsKey("nonce")) return Optional.of("a nonce: not a logout token");

        Instant now = clock.instant();
        Instant iat = jwt.issueTime().orElse(null);
        if (iat == null || iat.isAfter(now.plus(SKEW)) || iat.isBefore(now.minus(MAX_AGE))) {
            return Optional.of("issued at the wrong time");
        }
        Optional<Instant> exp = jwt.expirationTime();
        if (exp.isPresent() && !now.isBefore(exp.get().plus(SKEW))) return Optional.of("expired");

        JsonValue sidValue = claims.get("sid");
        String sid = sidValue != null && sidValue.type() == JsonValueType.STRING ? sidValue.asString().value() : null;
        String sub = jwt.subject().orElse(null);
        if (sid == null && sub == null) return Optional.of("names no session (sid) and no one (sub)");

        String jti = jwt.jwtId().orElse(null);
        if (jti == null) return Optional.of("no jti");
        forgetOld(now);
        if (seenJtis.putIfAbsent(jti, now) != null) return Optional.of("replayed");

        if (sid != null) {
            endedSids.put(sid, now);
            log.info("Back-channel logout: issuer session {} ended", sid);
        } else {
            endedSubjects.put(sub, now);
            log.info("Back-channel logout: every session of {} ended", sub);
        }
        return Optional.empty();
    }

    /**
     * Whether a session that signed in with issuer session {@code sid}, as {@code sub}, at
     * {@code signedInAt}, has been logged out by the issuer since.
     */
    boolean ended(String sid, String sub, Instant signedInAt) {
        if (sid != null && endedSids.containsKey(sid)) return true;
        Instant subjectEnded = sub == null ? null : endedSubjects.get(sub);
        return subjectEnded != null && (signedInAt == null || !signedInAt.isAfter(subjectEnded));
    }

    private void forgetOld(Instant now) {
        Instant last = lastForget;
        if (last != null && now.isBefore(last.plus(Duration.ofMinutes(5)))) return;
        lastForget = now;
        Instant logoutCutoff = now.minus(KEPT);
        endedSids.values().removeIf(t -> t.isBefore(logoutCutoff));
        endedSubjects.values().removeIf(t -> t.isBefore(logoutCutoff));
        Instant jtiCutoff = now.minus(MAX_AGE.plus(SKEW));
        seenJtis.values().removeIf(t -> t.isBefore(jtiCutoff));
    }

    private static Map<String, String> form(String body) {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        if (body == null) return form;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }
}
