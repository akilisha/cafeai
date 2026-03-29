package io.cafeai.core.internal;

import io.cafeai.core.CafeAI;
import io.cafeai.core.routing.*;
import io.helidon.common.parameters.Parameters;
import io.helidon.http.HeaderName;
import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapts a Helidon {@link ServerRequest} to the CafeAI {@link Request} interface.
 * Package-private -- never referenced directly by application code.
 */
final class HelidonRequest implements Request {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .configure(com.fasterxml.jackson.databind.DeserializationFeature
                .FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ServerRequest helidonReq;
    private final CafeAI app;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    // Body parsed by middleware -- set lazily
    private Map<String, Object> parsedBody;
    private byte[]  rawBody;
    private String  textBody;
    private Map<String, java.util.List<io.cafeai.core.routing.UploadedFile>> uploadedFiles;

    HelidonRequest(ServerRequest helidonReq, CafeAI app) {
        this.helidonReq = helidonReq;
        this.app = app;
    }

    @Override public CafeAI app() { return app; }

    @Override
    public String path() {
        return helidonReq.path().path();
    }

    @Override
    public String originalUrl() {
        String raw   = helidonReq.query().rawValue();
        String path  = helidonReq.path().path();
        return raw.isEmpty() ? path : path + "?" + raw;
    }

    @Override
    public String baseUrl() {
        return Objects.requireNonNullElse((String) attributes.get("_baseUrl"), "");
    }

    @Override
    public Route route() {
        return new RouteImpl(
            (String) attributes.getOrDefault("_routePattern", path()),
            method());
    }

    @Override
    public String method() {
        return helidonReq.prologue().method().text();
    }

    @Override
    public String protocol() {
        return helidonReq.isSecure() ? "https" : "http";
    }

    @Override
    public boolean secure() {
        return helidonReq.isSecure();
    }

    @Override
    public String hostname() {
        String host = helidonReq.headers()
            .value(HeaderNames.HOST)
            .orElse("");
        // Helidon 4 filter context may not expose HOST header directly;
        // fall back to the request authority (always available).
        if (host.isBlank()) {
            host = helidonReq.authority();
        }
        int colon = host.lastIndexOf(':');
        return colon >= 0 ? host.substring(0, colon) : host;
    }

    @Override
    public String ip() {
        return helidonReq.remotePeer().host();
    }

    @Override
    public List<String> ips() {
        var forwarded = header("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) return List.of(ip());
        return Arrays.stream(forwarded.split(","))
                     .map(String::trim)
                     .toList();
    }

    @Override
    public List<String> subdomains() {
        String host = hostname();
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return List.of();
        return List.of(Arrays.copyOf(parts, parts.length - 2));
    }

    @Override
    public boolean xhr() {
        return "XMLHttpRequest".equalsIgnoreCase(header("X-Requested-With"));
    }

    @Override
    public String params(String name) {
        // Helidon 4: pathParameters() returns Parameters -- use first(name)
        return helidonReq.path().pathParameters().first(name).orElse(null);
    }

    @Override
    public Map<String, String> params() {
        // Fix #7: Parameters has no toMap() in Helidon 4 -- iterate names()
        var result = new HashMap<String, String>();
        Parameters p = helidonReq.path().pathParameters();
        p.names().forEach(name ->
            p.first(name).ifPresent(v -> result.put(name, v)));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public String query(String name) {
        // Fix #8: UriQuery uses first(name) not get(name) in Helidon 4
        return helidonReq.query().first(name).orElse(null);
    }

    @Override
    public String query(String name, String defaultValue) {
        return helidonReq.query().first(name).orElse(defaultValue);
    }

    @Override
    public Map<String, List<String>> queryMap() {
        var result = new HashMap<String, List<String>>();
        helidonReq.query().names().forEach(name ->
            result.put(name, helidonReq.query().all(name)));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Map<String, Object> body() {
        return parsedBody != null
            ? Collections.unmodifiableMap(parsedBody)
            : Map.of();
    }

    @Override
    public String body(String key) {
        if (parsedBody == null) return null;
        Object val = parsedBody.get(key);
        return val != null ? val.toString() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T body(Class<T> type) {
        if (parsedBody == null) return null;
        // Map<String,Object> passthrough -- no re-serialization needed
        if (type == Map.class) return (T) parsedBody;
        // Deserialize through Jackson: parsedBody (Map) -> JSON bytes -> target type.
        // This is zero-copy relative to the wire: the map was already parsed from
        // the raw bytes; we convert it to the target type via Jackson's convertValue.
        try {
            return MAPPER.convertValue(parsedBody, type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Cannot deserialize request body to " + type.getSimpleName() +
                ": " + e.getMessage(), e);
        }
    }

    @Override public byte[] bodyBytes() { return rawBody; }
    @Override public String bodyText()  { return textBody; }

    @Override
    public io.cafeai.core.routing.UploadedFile file(String fieldName) {
        if (uploadedFiles == null) return null;
        var list = uploadedFiles.get(fieldName);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    @Override
    public java.util.List<io.cafeai.core.routing.UploadedFile> files(String fieldName) {
        if (uploadedFiles == null) return java.util.List.of();
        return uploadedFiles.getOrDefault(fieldName, java.util.List.of());
    }

    @Override
    public String header(String name) {
        // HeaderNames.create(lowerCase, defaultCase) for arbitrary user-supplied names.
        return helidonReq.headers()
            .value(HeaderNames.create(name.toLowerCase(), name))
            .orElse(null);
    }

    @Override
    public Map<String, String> headers() {
        var result = new HashMap<String, String>();
        // Headers extends Iterable<Header>.
        // Header.headerName().defaultCase() -> HTTP/1 canonical cased name.
        // Header.get() -> inherited from Value<String>, returns first value.
        // Header.value() is deprecated since Helidon 4.0.0 -- use get() instead.
        helidonReq.headers().forEach(h ->
            result.put(h.headerName().defaultCase(), h.get()));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public boolean is(String type) {
        String contentType = header("Content-Type");
        if (contentType == null) return false;
        // Note: the wildcard check uses startsWith/contains, not the literal
        // "* /json" string -- no Javadoc comment-terminator issue here
        if (type.startsWith("*/")) {
            return contentType.contains("/" + type.substring(2));
        }
        if (type.endsWith("/*")) {
            return contentType.startsWith(type.substring(0, type.length() - 2) + "/");
        }
        return contentType.contains(type);
    }

    @Override
    public String accepts(String... types) {
        String accept = header("Accept");
        if (accept == null) return types.length > 0 ? types[0] : null;
        for (String type : types) {
            if (accept.contains(type) || accept.contains("*/*")) return type;
        }
        return null;
    }

    @Override public String acceptsCharsets(String... c)  { return c.length > 0 ? c[0] : null; }
    @Override public String acceptsEncodings(String... e) { return e.length > 0 ? e[0] : null; }
    @Override public String acceptsLanguages(String... l) { return l.length > 0 ? l[0] : null; }

    @Override public Map<String, String> cookies()         { return Map.of(); }
    @Override public String cookie(String name)            { return null; }
    @Override public Map<String, String> signedCookies()   { return Map.of(); }
    @Override public String signedCookie(String name)      { return null; }

    @Override public boolean fresh()          { return false; }
    @Override public boolean stale()          { return true; }
    @Override public Object range(long size)  { return null; }

    @Override
    public Response response() {
        return (Response) attributes.get("_response");
    }

    @Override
    public boolean stream() {
        String accept = header("Accept");
        return accept != null && accept.contains("text/event-stream");
    }

    @Override
    public Request setAttribute(String key, Object value) {
        attributes.put(key, value);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T attribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) return null;
        return type.cast(value);
    }

    @Override public Object attribute(String key)     { return attributes.get(key); }
    @Override public boolean hasAttribute(String key) { return attributes.containsKey(key); }

    // Package-private setters used by body-parsing middleware
    void setParsedBody(Map<String, Object> body) { this.parsedBody = body; }
    void setRawBody(byte[] body)                 { this.rawBody = body; }
    void setTextBody(String body)                { this.textBody = body; }
    void setUploadedFiles(Map<String, java.util.List<io.cafeai.core.routing.UploadedFile>> files) {
        this.uploadedFiles = files;
    }

    /** Package-private: exposes underlying Helidon request for body reading in middleware. */
    ServerRequest helidonServerRequest()         { return helidonReq; }

    private record RouteImpl(String path, String method) implements Route {}
}
