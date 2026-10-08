package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.identity.Identity;
import io.cafeai.identity.Auth;
import io.cafeai.identity.dev.FakeIssuer;

import java.util.List;
import java.util.Map;

/**
 * IdentityExample: an API whose callers are verified, with no identity provider to install.
 *
 * <p>It starts {@link FakeIssuer}, a stand-in OpenID Connect issuer for development, protects
 * the API with {@code Auth.bearer}, checks what the issuer granted with {@code Auth.require}, and
 * prints {@code curl} commands with ready-made tokens for two callers. In production, replace
 * {@code fake.issuer()} with {@code Issuer.discover("https://<your issuer>")}; nothing else
 * changes. See DEVELOPER_GUIDE section 32.
 *
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.IdentityExample
 * </pre>
 */
public class IdentityExample {

    public static void main(String[] args) {
        FakeIssuer fake = FakeIssuer.start();   // development only: signs tokens for anyone
        var app = CafeAI.create();

        // ── Every request needs a valid token for this API ───────────────────────────
        app.filter(Auth.bearer(fake.issuer(), "orders-api"));

        // ── Who is calling ──────────────────────────────────────────────────────────
        app.get("/me", (req, res, next) -> {
            Identity who = req.identity().orElseThrow();
            res.json(Map.of(
                "subject", who.subject(),
                "name", who.name().orElse(""),
                "groups", who.groups(),
                "scopes", who.scopes()));
        });

        // ── What the issuer granted: a scope, a group ─────────────────────────────────
        app.get("/orders", Auth.require(Auth.scope("orders:read")),
            (req, res, next) -> res.json(Map.of("orders", List.of("A-1001", "A-1002"))));
        app.get("/ledger", Auth.require(Auth.group("finance")),
            (req, res, next) -> res.json(Map.of("balance", 12_000)));

        app.listen(8080, () -> {
            String alice = fake.token().subject("alice").name("Alice").audience("orders-api")
                .scope("orders:read").groups("finance").sign();
            String bob = fake.token().subject("bob").name("Bob").audience("orders-api")
                .scope("orders:read").groups("staff").sign();
            System.out.println("""

                Identity example on http://localhost:8080 (tokens from a fake issuer at %s)

                  curl -i localhost:8080/me                                  # 401: no token
                  curl -H "Authorization: Bearer %s" localhost:8080/me
                  curl -H "Authorization: Bearer %s" localhost:8080/ledger   # Alice is in finance: 200
                  curl -i -H "Authorization: Bearer %s" localhost:8080/ledger   # Bob is not: 403
                """.formatted(fake.id(), alice, alice, bob));
        });
    }
}
