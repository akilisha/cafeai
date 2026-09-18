package io.cafeai.core.routing;

import io.cafeai.core.CafeAI;

import java.util.List;
import java.util.Map;

/**
 * The per-request HTTP request object.
 *
 * <p>Mirrors Express {@code req} pound-for-pound per ADR-005.
 * Every method name and semantic is drawn directly from the Express 4.x API.
 * Where Java paradigm differences require translation, ADR-005 §4 documents
 * the rationale.
 *
 * <p>Key translations from Express:
 * <ul>
 *   <li>{@code req.params} (object) → {@link #params(String)} / {@link #params()}</li>
 *   <li>{@code req.get(header)} → {@link #header(String)} (avoids getter naming conflict)</li>
 *   <li>{@code req.method} (string) → {@link #method()} returns {@code String} uppercase</li>
 *   <li>{@code req.ips} (array) → {@link #ips()} returns {@code List<String>}</li>
 *   <li>{@code req.res} → {@link #response()} (more descriptive as a Java method name)</li>
 *   <li>Dynamic property assignment ({@code req.user = ...}) → {@link #setAttribute(String, Object)}</li>
 * </ul>
 *
 * <p>CafeAI extensions (no Express equivalent):
 * <ul>
 *   <li>{@link #stream()} — detects SSE streaming clients</li>
 *   <li>{@link #attribute(String, Class)} — typed request attribute carrier</li>
 * </ul>
 */
public interface Request {

    // ── Application Reference ─────────────────────────────────────────────────

    /** Returns the CafeAI application instance. Mirrors Express {@code req.app}. */
    CafeAI app();

    // ── URL and Routing ───────────────────────────────────────────────────────

    /**
     * The URL path of the request, without query string.
     * Mirrors Express {@code req.path}.
     * Example: {@code "/users/42"} for {@code "GET /users/42?include=profile"}
     */
    String path();

    /**
     * The full original URL including query string.
     * Mirrors Express {@code req.originalUrl}.
     * Example: {@code "/users/42?include=profile"}
     */
    String originalUrl();

    /**
     * The path prefix at which the matched router was mounted.
     * Mirrors Express {@code req.baseUrl}.
     * Example: {@code "/api/v1"} for a router mounted at {@code /api/v1}.
     */
    String baseUrl();

    /**
     * The matched route object — path pattern and HTTP method.
     * Mirrors Express {@code req.route}.
     */
    Route route();

    // ── HTTP Method ───────────────────────────────────────────────────────────

    /**
     * The HTTP method as an uppercase string.
     * Mirrors Express {@code req.method}.
     * Example: {@code "GET"}, {@code "POST"}, {@code "DELETE"}.
     *
     * <p>ADR-005 note: Express returns a raw string. CafeAI returns a typed
     * uppercase string. An {@code HttpMethod} enum is available for comparison:
     * {@code req.method().equals(HttpMethod.GET)}.
     */
    String method();

    // ── Protocol and Security ─────────────────────────────────────────────────

    /**
     * The request protocol — {@code "http"} or {@code "https"}.
     * Mirrors Express {@code req.protocol}.
     */
    String protocol();

    /**
     * {@code true} if the request was made over HTTPS.
     * Shorthand for {@code req.protocol().equals("https")}.
     * Mirrors Express {@code req.secure}.
     */
    boolean secure();

    /** The hostname from the {@code Host} header, port stripped. Mirrors Express {@code req.hostname}. */
    String hostname();

    /**
     * The remote IP address. Respects {@code X-Forwarded-For} when
     * {@code Setting.TRUST_PROXY} is enabled.
     * Mirrors Express {@code req.ip}.
     */
    String ip();

    /**
     * Ordered list of IPs from the proxy chain (when {@code TRUST_PROXY} enabled).
     * Mirrors Express {@code req.ips} — returns {@code List<String>} not JS array.
     */
    List<String> ips();

    /**
     * Subdomains of the hostname, ordered from most specific.
     * Offset controlled by {@code Setting.SUBDOMAIN_OFFSET} (default: 2).
     * Mirrors Express {@code req.subdomains}.
     */
    List<String> subdomains();

    /**
     * {@code true} if the request was made with {@code X-Requested-With: XMLHttpRequest}.
     * Mirrors Express {@code req.xhr}.
     */
    boolean xhr();

    // ── Route and Query Parameters ────────────────────────────────────────────

    /**
     * Returns the value of a named route path parameter.
     * Mirrors Express {@code req.params.name}.
     *
     * <p>Route params are defined with {@code :name} syntax:
     * <pre>{@code
     *   app.get("/users/:id", (req, res, next) -> {
     *       String id = req.params("id");
     *   });
     * }</pre>
     */
    String params(String name);

    /**
     * Returns all route path parameters as an unmodifiable map.
     * Mirrors Express {@code req.params} object.
     */
    Map<String, String> params();

    /**
     * Returns the value of a named query string parameter.
     * Returns {@code null} if absent.
     * Mirrors Express {@code req.query.name}.
     *
     * <p>Example: for {@code GET /search?q=cafeai&page=2}, {@code req.query("q")} → {@code "cafeai"}.
     */
    String query(String name);

    /**
     * Returns the value of a query parameter, or {@code defaultValue} if absent.
     */
    String query(String name, String defaultValue);

    /**
     * Returns all query parameters as a multi-value map.
     * Values are lists to support repeated params: {@code ?tag=a&tag=b}.
     */
    Map<String, List<String>> queryMap();

    // ── Body ─────────────────────────────────────────────────────────────────

    /**
     * Returns the parsed request body as an unmodifiable map.
     * Requires {@code CafeAI.json()} or {@code CafeAI.urlencoded()} middleware.
     * Returns an empty map if no body middleware is registered or body is absent.
     * Mirrors Express {@code req.body} (as object).
     */
    Map<String, Object> body();

    /**
     * Returns a single key from the parsed request body.
     * Returns {@code null} if the key is absent.
     * Mirrors Express {@code req.body.key}.
     */
    String body(String key);

    /**
     * Deserializes the request body into the given type via Jackson.
     * Mirrors Express {@code req.body} accessed as a typed object.
     *
     * <pre>{@code
     *   app.post("/users", (req, res, next) -> {
     *       UserDto dto = req.body(UserDto.class);
     *       res.status(201).json(userService.create(dto));
     *   });
     * }</pre>
     */
    <T> T body(Class<T> type);

    /**
     * Returns the raw request body as bytes.
     * Requires {@code CafeAI.raw()} middleware.
     * Returns {@code null} if raw middleware is not registered.
     */
    byte[] bodyBytes();

    /**
     * Returns the request body as a plain string.
     * Requires {@code CafeAI.text()} middleware.
     * Returns {@code null} if text middleware is not registered.
     */
    String bodyText();

    /**
     * Returns the first uploaded file for the given form field name.
     * Requires {@code CafeAI.multipart()} middleware.
     *
     * <pre>{@code
     *   app.filter(CafeAI.multipart());
     *
     *   app.post("/upload", (req, res, next) -> {
     *       UploadedFile doc = req.file("document");
     *       if (doc == null) { res.status(400).send("no file"); return; }
     *       doc.saveToDirectory(Path.of("/uploads"));
     *       res.json(Map.of("name", doc.originalName(), "size", doc.size()));
     *   });
     * }</pre>
     *
     * @param fieldName the multipart form field name
     * @return the uploaded file, or {@code null} if not present
     */
    UploadedFile file(String fieldName);

    /**
     * Returns all uploaded files for the given form field name.
     * Used when multiple files are uploaded under the same field.
     * Requires {@code CafeAI.multipart()} middleware.
     *
     * @param fieldName the multipart form field name
     * @return list of uploaded files (empty if none)
     */
    java.util.List<UploadedFile> files(String fieldName);

    // ── Headers ───────────────────────────────────────────────────────────────

    /**
     * Returns the value of the named request header (case-insensitive).
     *
     * <p>ADR-005 note: Express uses {@code req.get(name)} for header access.
     * CafeAI uses {@code req.header(name)} to avoid conflict with Java bean
     * getter naming conventions.
     *
     * <pre>{@code
     *   String auth = req.header("Authorization");
     * }</pre>
     */
    String header(String name);

    /**
     * Returns all request headers as an unmodifiable case-insensitive map.
     */
    Map<String, String> headers();

    // ── Content Negotiation ───────────────────────────────────────────────────

    /**
     * Checks if the {@code Content-Type} of the request matches the given type.
     * Supports MIME type wildcards.
     * Mirrors Express {@code req.is(type)}.
     *
     * <pre>{@code
     *   if (req.is("application/json")) { ... }
     *   if (req.is("*" + "/json")) { ... }   // wildcard subtype match
     * }</pre>
     */
    boolean is(String type);

    /**
     * Returns the offered content type the client prefers, per its {@code Accept} header:
     * highest {@code q} first, then the most specific range, then the order you listed.
     * A type with {@code q=0} is refused. Accepts full MIME types or the short names
     * {@code json}, {@code html}, {@code text}, {@code xml}, {@code css}, {@code js}; the
     * value returned is the one you passed in. With no {@code Accept} header the first offer
     * wins. Returns {@code null} if none is acceptable.
     * Mirrors Express {@code req.accepts()}.
     */
    String accepts(String... types);

    /** Best {@code Accept-Charset} match (same rules as {@link #accepts}), or {@code null}. Mirrors Express {@code req.acceptsCharsets()}. */
    String acceptsCharsets(String... charsets);

    /** Best {@code Accept-Encoding} match (same rules as {@link #accepts}), or {@code null}. Mirrors Express {@code req.acceptsEncodings()}. */
    String acceptsEncodings(String... encodings);

    /**
     * Best {@code Accept-Language} match (same rules as {@link #accepts}), or {@code null}.
     * A range matches more specific tags, so {@code en} accepts an offered {@code en-US}.
     * Mirrors Express {@code req.acceptsLanguages()}.
     */
    String acceptsLanguages(String... languages);

    // ── Cookies ───────────────────────────────────────────────────────────────

    /**
     * The request's cookies, parsed from the {@code Cookie} header, as an unmodifiable
     * map (empty if there are none). No middleware is needed. Values are percent-decoded
     * when validly encoded, and the first occurrence of a repeated name wins.
     * Mirrors Express {@code req.cookies}.
     */
    Map<String, String> cookies();

    /** The value of a named cookie, or {@code null} if the request did not send it. */
    String cookie(String name);

    // ── Cache Freshness ───────────────────────────────────────────────────────

    /**
     * {@code true} if the client's cached copy is still current, so a {@code 304 Not Modified}
     * is appropriate. Compares {@code If-None-Match} to the response's {@code ETag} (weak
     * comparison), or, when that header is absent, {@code If-Modified-Since} to its
     * {@code Last-Modified}. Only {@code GET}/{@code HEAD} with a 2xx or 304 response can be
     * fresh, and {@code Cache-Control: no-cache} forces stale. <strong>Set the response's
     * {@code ETag} / {@code Last-Modified} before calling this</strong> — it reads them.
     * Mirrors Express {@code req.fresh}.
     */
    boolean fresh();

    /**
     * {@code true} if the request is "stale" — the opposite of {@link #fresh()}.
     * Mirrors Express {@code req.stale}.
     */
    boolean stale();

    // ── Paired Response ───────────────────────────────────────────────────────

    /**
     * Returns the paired response object for this request.
     *
     * <p>ADR-005 note: Express uses {@code req.res} as a property.
     * CafeAI uses {@code req.response()} — more descriptive as a Java method name.
     */
    Response response();

    // ── CafeAI Extensions (no Express equivalent) ─────────────────────────────

    /**
     * Returns {@code true} if the client expects a Server-Sent Events stream.
     * Detected via {@code Accept: text/event-stream} header.
     *
     * <p>This is the AI-native companion to {@link #xhr()} — identifies clients
     * that expect streaming token responses rather than single JSON responses.
     *
     * <pre>{@code
     *   app.post("/chat", (req, res, next) -> {
     *       if (req.stream()) {
     *           res.stream(app.prompt(req.body("message")));
     *       } else {
     *           res.send(app.prompt(req.body("message")).call().text());
     *       }
     *   });
     * }</pre>
     */
    boolean stream();

    /**
     * Sets a typed request attribute — the Java-idiomatic replacement for
     * Express's dynamic property assignment ({@code req.user = ...}).
     *
     * <p>Attributes are set in middleware and read in downstream handlers or
     * further middleware. Thread-safe. Accessible across the full request chain.
     *
     * <p>Pre-defined attribute key constants are available in {@code Attributes}.
     *
     * <pre>{@code
     *   // In auth middleware:
     *   req.setAttribute(Attributes.AUTH_PRINCIPAL, principal);
     *
     *   // In route handler:
     *   Principal p = req.attribute(Attributes.AUTH_PRINCIPAL, Principal.class);
     * }</pre>
     */
    Request setAttribute(String key, Object value);

    /**
     * Retrieves a typed request attribute set by upstream middleware.
     *
     * @throws ClassCastException if the stored value is not assignable to {@code type}
     */
    <T> T attribute(String key, Class<T> type);

    /**
     * Retrieves an untyped request attribute. Returns {@code null} if absent.
     */
    Object attribute(String key);

    /**
     * Returns {@code true} if an attribute with the given key has been set.
     */
    boolean hasAttribute(String key);
}
