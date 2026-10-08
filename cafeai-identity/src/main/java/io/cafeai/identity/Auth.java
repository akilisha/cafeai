package io.cafeai.identity;

import java.util.Set;

/**
 * Entry point for identity middleware.
 *
 * <pre>{@code
 *   var issuer = Issuer.discover("https://issuer.example.com/realms/acme");
 *   app.filter(Auth.bearer(issuer, "orders-api"));
 *
 *   app.get("/me", (req, res, next) ->
 *       res.json(Map.of("subject", req.identity().orElseThrow().subject())));
 * }</pre>
 *
 * <p>CafeAI never decides who may do what. The issuer does, through the tokens it signs; this
 * middleware verifies those tokens and carries the caller's identity through the app.
 */
public final class Auth {

    private Auth() {}

    /**
     * Requires a valid access token from {@code issuer} on every request it covers.
     *
     * @param issuer    the issuer whose tokens are accepted
     * @param audiences this service's identifier at the issuer; a token must name at least one
     *                  of them in {@code aud}, so a token issued for another service is refused
     */
    public static BearerAuth bearer(Issuer issuer, String... audiences) {
        return new BearerAuth(issuer, Set.of(audiences));
    }
}
