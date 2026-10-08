package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.identity.Identity;
import io.cafeai.identity.Auth;
import io.cafeai.identity.Issuer;

import java.util.List;
import java.util.Map;

/**
 * IdentityOrdersApi: an API that only verified callers can use, for the terminal CLI
 * {@code cafeai-examples/identity/orders-cli.java} to call.
 *
 * <p>Tokens must be issued by the demo Keycloak for {@code orders-api}. What each route needs comes
 * from the token, decided by the issuer: {@code /me} and {@code /orders} any signed-in caller,
 * {@code /ledger} the {@code finance} group (alice has it, bob doesn't).
 *
 * <pre>
 *   docker compose -f cafeai-examples/identity/docker-compose.yml up -d   # Keycloak on :8180
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.IdentityOrdersApi
 *   jbang cafeai-examples/identity/orders-cli.java ledger                   # signs in from the terminal
 * </pre>
 */
public class IdentityOrdersApi {

    public static void main(String[] args) {
        String issuerId = System.getenv().getOrDefault("ISSUER", "http://localhost:8180/realms/cafeai-demo");
        var app = CafeAI.create();
        app.filter(Auth.bearer(Issuer.discover(issuerId), "orders-api"));

        app.get("/me", Auth.signedIn(), (req, res, next) -> {
            Identity who = req.identity().orElseThrow();
            res.json(Map.of("name", who.name().orElse(""), "groups", who.groups(),
                "via", who.claim("azp").orElse("?")));
        });
        app.get("/orders", Auth.signedIn(), (req, res, next) ->
            res.json(Map.of("orders", List.of("A-1001 coffee beans", "A-1002 grinder"))));
        app.get("/ledger", Auth.require(Auth.group("finance")), (req, res, next) ->
            res.json(Map.of("balance", 12_000, "currency", "EUR")));

        app.listen(8081, () -> System.out.println(
            "\nOrders API on http://localhost:8081 (tokens from " + issuerId + " for orders-api)\n"));
    }
}
