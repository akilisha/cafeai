package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.Request;

import java.util.List;
import java.util.Optional;
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

    /**
     * Browser sign-in with OpenID Connect (authorization code flow with PKCE). Tokens are kept in
     * the server-side session, so register a session store first:
     *
     * <pre>{@code
     *   app.filter(Middleware.session(store));
     *   app.filter(Auth.login(issuer, "orders-web", secret, "https://orders.example.com/auth/callback"));
     * }</pre>
     *
     * @param clientId     this app's client id at the issuer
     * @param clientSecret its client secret
     * @param redirectUri  the absolute callback URL registered at the issuer; its path is served here
     */
    public static BrowserLogin login(Issuer issuer, String clientId, String clientSecret, String redirectUri) {
        return new BrowserLogin(issuer, clientId, clientSecret, redirectUri);
    }

    /**
     * Protects the app's MCP endpoint ({@code app.mcp()}) per the MCP authorization
     * specification: tokens must be issued for {@code resourceUri} itself, and the endpoint's
     * Protected Resource Metadata (RFC 9728) tells MCP clients which issuer to sign in with.
     *
     * @param resourceUri the MCP endpoint's absolute URL, e.g. {@code https://orders.example.com/mcp}
     */
    public static McpAuth mcp(CafeAI app, Issuer issuer, String resourceUri) {
        return new McpAuth(app, issuer, resourceUri);
    }

    /**
     * The CSRF token of the request's signed-in browser session, for pages to put in an
     * {@code X-CSRF-Token} header or a {@code _csrf} form field; empty when not signed in.
     */
    public static Optional<String> csrfToken(Request req) {
        return BrowserLogin.csrfToken(req);
    }

    /**
     * Lets a request through only if its caller meets every requirement. Put it after
     * {@link #bearer}, on a route, a router or a filter:
     *
     * <pre>{@code
     *   app.filter(Auth.bearer(issuer, "orders-api"));
     *   app.get("/orders", Auth.require(Auth.scope("orders:read")), listOrders);
     *   app.post("/refunds", Auth.require(Auth.role("approver"), Auth.scope("orders:write")), refund);
     *   app.get("/reports", Auth.require(Auth.anyOf(Auth.group("finance"), Auth.role("auditor"))), reports);
     * }</pre>
     *
     * <p>An anonymous request gets {@code 401}; an identity that falls short gets {@code 403}.
     */
    public static Middleware require(Requirement... requirements) {
        return new RequireAuth(List.of(requirements));
    }

    /**
     * Lets a request through only if it has a verified caller, whatever the token grants;
     * anonymous requests get {@code 401}. For routes where being signed in is the requirement:
     *
     * <pre>{@code
     *   app.post("/chat", Auth.signedIn(), chat);
     * }</pre>
     */
    public static Middleware signedIn() {
        return (req, res, next) -> {
            if (req.identity().isEmpty()) {
                res.status(401).set("WWW-Authenticate", ResourceMetadata.challenge("Bearer",
                        req.attribute(ResourceMetadata.ATTRIBUTE, String.class))).end();
                return;
            }
            next.run();
        };
    }

    /** The token's {@code scope} must include {@code scope}. */
    public static Requirement scope(String scope) {
        return Requirement.scope(scope);
    }

    /** The token's {@code roles} must include {@code role} (RFC 9068). */
    public static Requirement role(String role) {
        return Requirement.role(role);
    }

    /** The token's {@code groups} must include {@code group} (RFC 9068). */
    public static Requirement group(String group) {
        return Requirement.group(group);
    }

    /** The token's {@code entitlements} must include {@code entitlement} (RFC 9068). */
    public static Requirement entitlement(String entitlement) {
        return Requirement.entitlement(entitlement);
    }

    /** At least one of {@code options} must hold. */
    public static Requirement anyOf(Requirement... options) {
        return Requirement.anyOf(List.of(options));
    }
}
