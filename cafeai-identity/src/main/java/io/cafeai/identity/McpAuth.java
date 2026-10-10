package io.cafeai.identity;

import io.cafeai.core.Attributes;
import io.cafeai.core.CafeAI;
import io.cafeai.core.Locals;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.identity.IdentityMode;
import io.cafeai.core.internal.CurrentRequest;
import io.helidon.http.HeaderNames;
import io.helidon.http.Status;
import io.helidon.webserver.http.HttpRouting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Protects an app's MCP endpoint ({@code app.mcp()}) as the MCP authorization specification asks:
 * the endpoint is an OAuth 2.0 resource server, and publishes Protected Resource Metadata
 * (RFC 9728) naming the issuer, so an MCP client can find where to sign in by itself.
 *
 * <pre>{@code
 *   app.mcp().tools(new OrderTools());
 *   Auth.mcp(app, issuer, "https://orders.example.com/mcp").scope("orders:read");
 * }</pre>
 *
 * <ul>
 *   <li>{@code GET /.well-known/oauth-protected-resource/mcp} serves the metadata: the resource,
 *       its issuer, and the scopes it needs;</li>
 *   <li>every request to the MCP endpoint needs an access token issued for the resource itself:
 *       its {@code aud} must be the resource's URL (the token audience binding the MCP
 *       specification requires, so a token for another service can't be replayed here);</li>
 *   <li>without one: {@code 401} with {@code WWW-Authenticate: Bearer resource_metadata="..."};
 *       without the scopes: {@code 403} {@code insufficient_scope}.</li>
 * </ul>
 *
 * <p>The MCP endpoint is mounted outside CafeAI's filters, so {@code app.filter(Auth.bearer(...))}
 * does not cover it; this does. Route tools forward the caller's token to their route, so a route
 * behind {@code Auth.bearer} must accept the resource's URL as an audience too. {@code @Tool}
 * objects find the verified caller in {@code Identity.current()}.
 */
public final class McpAuth {

    private static final Logger log = LoggerFactory.getLogger(McpAuth.class);

    /** RFC 9728 3.1: inserted between the host and the resource's path. */
    static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

    private final Issuer issuer;
    private final URI resource;
    private final String path;
    private final String metadataPath;
    private final String metadataUrl;
    private final Set<String> scopes = new LinkedHashSet<>();
    private volatile Clock clock = Clock.systemUTC();
    private volatile TokenValidator validator;

    McpAuth(CafeAI app, Issuer issuer, String resourceUri) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.resource = URI.create(Objects.requireNonNull(resourceUri, "resourceUri"));
        if (!resource.isAbsolute() || resource.getPath() == null || resource.getPath().isEmpty()
                || resource.getPath().equals("/") || resource.getFragment() != null) {
            throw new IllegalArgumentException("resourceUri must be the MCP endpoint's absolute URL, "
                    + "e.g. https://orders.example.com/mcp: " + resourceUri);
        }
        this.path = resource.getPath();
        this.metadataPath = WELL_KNOWN + path;
        String base = resource.getScheme() + "://" + resource.getRawAuthority();
        this.metadataUrl = base + metadataPath;
        IdentityMode.enable();
        app.local(Locals.MCP_PROTECTED, path);
        app.helidon().bypass(WELL_KNOWN).routing(this::install);
    }

    /** Scopes every MCP request's token must carry; also published in the metadata. */
    public McpAuth scope(String... required) {
        for (String s : required) {
            Requirement.scope(s);   // validates it as an RFC 6749 scope token
            scopes.add(s);
        }
        return this;
    }

    McpAuth clock(Clock clock) {
        this.clock = clock;
        this.validator = null;
        return this;
    }

    private TokenValidator validator() {
        TokenValidator v = validator;
        if (v == null) {
            v = new TokenValidator(issuer, Set.of(resource.toString()), TokenValidator.DEFAULT_ALGORITHMS,
                    Duration.ofSeconds(60), false, clock);
            validator = v;
        }
        return v;
    }

    private void install(HttpRouting.Builder routing) {
        routing.get(metadataPath, (req, res) -> res
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .header(HeaderNames.CACHE_CONTROL, "max-age=3600")
                .send(metadata()));
        routing.addFilter((chain, req, res) -> {
            String p = req.path().path();
            if (!p.equals(path) && !p.startsWith(path + "/")) {
                chain.proceed();
                return;
            }
            String header = req.headers().first(HeaderNames.AUTHORIZATION).orElse(null);
            String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                    ? header.substring(7).trim() : null;
            if (token == null || token.isEmpty()) {
                res.status(Status.UNAUTHORIZED_401)
                        .header(HeaderNames.WWW_AUTHENTICATE, "Bearer resource_metadata=\"" + metadataUrl + "\"")
                        .send();
                return;
            }
            TokenValidator.Result result;
            try {
                result = validator().validate(token);
            } catch (IdentityException e) {
                log.error("Can't validate MCP tokens: {}", e.getMessage());
                res.status(Status.SERVICE_UNAVAILABLE_503).send();
                return;
            }
            if (!result.valid()) {
                res.status(Status.UNAUTHORIZED_401).header(HeaderNames.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", error_description=\"" + result.failure().description
                        + "\", resource_metadata=\"" + metadataUrl + "\"").send();
                return;
            }
            Identity who = result.identity();
            if (!who.scopes().containsAll(scopes)) {
                res.status(Status.FORBIDDEN_403).header(HeaderNames.WWW_AUTHENTICATE,
                        "Bearer error=\"insufficient_scope\", scope=\"" + String.join(" ", scopes)
                        + "\", resource_metadata=\"" + metadataUrl + "\"").send();
                return;
            }
            // The MCP endpoint runs as a CafeAI request (helidon().scoped): the tools it calls see
            // the caller in Identity.current(), and model calls can be made on their behalf.
            CurrentRequest.get().ifPresent(r -> {
                r.setAttribute(Attributes.IDENTITY, who);
                r.setAttribute(BearerAuth.ACCESS_TOKEN, token);
            });
            chain.proceed();
        });
    }

    /** The RFC 9728 document: this resource, who issues its tokens, and the scopes it needs. */
    String metadata() {
        String scopeList = scopes.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        return "{\"resource\":\"" + resource + "\","
                + "\"authorization_servers\":[\"" + issuer.id() + "\"],"
                + "\"bearer_methods_supported\":[\"header\"]"
                + (scopes.isEmpty() ? "" : ",\"scopes_supported\":[" + scopeList + "]")
                + "}";
    }
}
