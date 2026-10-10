package io.cafeai.identity;

import io.cafeai.core.CafeAI;
import io.helidon.http.HeaderNames;

import java.net.URI;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728): a resource's own description of itself
 * (its identifier, the issuer of its tokens, the scopes it knows), so a client refused with a
 * {@code 401} can find out where to get a token by itself.
 */
final class ResourceMetadata {

    /** RFC 9728 3.1: inserted between the host and the resource's path. */
    static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

    /**
     * The request attribute naming the metadata of the resource a request was made to, so a
     * challenge further down ({@code Auth.require}, {@code Auth.signedIn}) can point at it too.
     */
    static final String ATTRIBUTE = "cafeai.identity.resource_metadata";

    final URI resource;
    final String path;
    final String url;

    private ResourceMetadata(URI resource) {
        this.resource = resource;
        String p = resource.getPath() == null || resource.getPath().equals("/") ? "" : resource.getPath();
        this.path = WELL_KNOWN + p;
        this.url = resource.getScheme() + "://" + resource.getRawAuthority() + path;
    }

    /**
     * The metadata of {@code resourceUri}: an absolute https or http URL with no query or
     * fragment (RFC 9728 1.2).
     */
    static ResourceMetadata of(String resourceUri, String what) {
        URI uri;
        try {
            uri = URI.create(resourceUri);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(what + " must be an absolute URL: " + resourceUri, e);
        }
        if (!uri.isAbsolute() || uri.getRawAuthority() == null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getScheme().equals("https") || uri.getScheme().equals("http"))) {
            throw new IllegalArgumentException(what + " must be an absolute http(s) URL with no query or fragment, "
                    + "e.g. https://orders.example.com: " + resourceUri);
        }
        return new ResourceMetadata(uri);
    }

    /** Serves the document at its well-known path, outside CafeAI's filters: a client needs it before it has a token. */
    void serve(CafeAI app, Issuer issuer, Collection<String> scopes) {
        app.helidon().bypass(WELL_KNOWN).routing(r -> r.get(path, (req, res) -> res
                .header(HeaderNames.CONTENT_TYPE, "application/json")
                .header(HeaderNames.CACHE_CONTROL, "max-age=3600")
                .send(json(issuer, scopes))));
    }

    /** The RFC 9728 document: this resource, who issues its tokens, and the scopes it needs. */
    String json(Issuer issuer, Collection<String> scopes) {
        String scopeList = scopes.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        return "{\"resource\":\"" + resource + "\","
                + "\"authorization_servers\":[\"" + issuer.id() + "\"],"
                + "\"bearer_methods_supported\":[\"header\"]"
                + (scopes.isEmpty() ? "" : ",\"scopes_supported\":[" + scopeList + "]")
                + "}";
    }

    /** {@code challenge} with this resource's metadata named in it ({@code Bearer} alone when bare). */
    static String challenge(String challenge, String metadataUrl) {
        if (metadataUrl == null) return challenge;
        String param = "resource_metadata=\"" + metadataUrl + "\"";
        return challenge.equals("Bearer") ? "Bearer " + param : challenge + ", " + param;
    }
}
