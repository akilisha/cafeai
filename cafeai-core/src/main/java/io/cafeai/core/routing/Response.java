package io.cafeai.core.routing;

import io.cafeai.core.CafeAI;
import java.util.concurrent.Flow;

import java.nio.file.Path;
import java.util.Map;

/**
 * The per-request HTTP response builder.
 *
 * <p>Mirrors Express {@code res} pound-for-pound per ADR-005.
 * All methods are fluent — they return {@code this} unless they
 * terminate the response (send, json, end, stream, redirect, download).
 *
 * <p>Key translations from Express (ADR-005 §5):
 * <ul>
 *   <li>{@code res.get(header)} → {@link #header(String)} (naming convention conflict)</li>
 *   <li>{@code res.download(path)} → {@link #download(Path)} (typed Path)</li>
 *   <li>{@code res.sendFile(path)} → {@link #sendFile(Path)} (typed Path)</li>
 *   <li>{@code res.links({...})} → {@link #links(Map)} (Map over object literal)</li>
 *   <li>{@code res.locals.x = v} → {@link #local(String, Object)} + ScopedValue</li>
 * </ul>
 *
 * <p>Intentionally omitted (ADR-005 §8):
 * <ul>
 *   <li>{@code res.jsonp()} — JSONP is a legacy CORS workaround, obsolete</li>
 * </ul>
 *
 * <p>CafeAI extensions (no Express equivalent):
 * <ul>
 *   <li>{@link #stream(java.util.concurrent.Flow.Publisher)} — SSE token streaming</li>
 * </ul>
 */
public interface Response {

    // ── Application Reference ─────────────────────────────────────────────────

    /** Returns the CafeAI application instance. Mirrors Express {@code res.app}. */
    CafeAI app();

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Sets the HTTP status code. Returns {@code this} for chaining.
     * Mirrors Express {@code res.status(code)}.
     *
     * <pre>{@code
     *   res.status(201).json(created);
     *   res.status(204).end();
     * }</pre>
     */
    Response status(int code);

    /**
     * Sends the HTTP status code with its standard reason phrase as the body.
     * Terminates the response.
     * Mirrors Express {@code res.sendStatus(code)}.
     *
     * <pre>{@code
     *   res.sendStatus(404);  // 404 Not Found
     *   res.sendStatus(204);  // 204 No Content
     * }</pre>
     */
    void sendStatus(int code);

    // ── Body Senders ──────────────────────────────────────────────────────────

    /**
     * Sends a text response. Sets {@code Content-Type: text/html} if not already set.
     * Terminates the response.
     * Mirrors Express {@code res.send(body)}.
     */
    void send(String body);

    /**
     * Sends a binary response. Sets {@code Content-Type: application/octet-stream}
     * if not already set. Terminates the response.
     * Mirrors Express {@code res.send(buffer)}.
     */
    void send(byte[] body);

    /**
     * Serializes {@code body} to JSON via Jackson and sends it.
     * Sets {@code Content-Type: application/json; charset=utf-8} automatically.
     * Terminates the response.
     * Mirrors Express {@code res.json(body)}.
     *
     * <pre>{@code
     *   res.json(Map.of("status", "ok"));
     *   res.status(201).json(created);
     * }</pre>
     */
    void json(Object body);

    /**
     * Terminates the response without sending a body.
     * Useful for {@code 204 No Content} responses.
     * Mirrors Express {@code res.end()}.
     */
    void end();

    // ── Headers ───────────────────────────────────────────────────────────────

    /**
     * Sets a response header, overwriting any existing value.
     * Returns {@code this} for chaining.
     * Mirrors Express {@code res.set(field, value)}.
     */
    Response set(String field, String value);

    /**
     * Sets multiple response headers at once.
     * Returns {@code this} for chaining.
     * Mirrors Express {@code res.set(object)}.
     */
    Response set(Map<String, String> headers);

    /**
     * Appends to a response header, comma-separating multiple values.
     * Returns {@code this} for chaining.
     * Mirrors Express {@code res.append(field, value)}.
     */
    Response append(String field, String value);

    /**
     * Returns the current value of the named response header.
     *
     * <p>ADR-005 note: Express uses {@code res.get(field)}.
     * CafeAI uses {@code res.header(field)} to avoid getter naming conflict.
     */
    String header(String field);

    /**
     * Sets the {@code Content-Type} header. Accepts short names ({@code "json"},
     * {@code "html"}, {@code "text"}) or full MIME types.
     * Returns {@code this} for chaining.
     * Mirrors Express {@code res.type(type)}.
     */
    Response type(String type);

    /**
     * Adds a field to the {@code Vary} header. No duplicates added.
     * Returns {@code this} for chaining.
     * Mirrors Express {@code res.vary(field)}.
     */
    Response vary(String field);

    /**
     * Populates the {@code Link} response header per RFC 5988.
     * Returns {@code this} for chaining.
     * Mirrors Express {@code res.links(links)} — uses {@code Map} not JS object literal.
     *
     * <pre>{@code
     *   res.links(Map.of("next", "/page/2", "prev", "/page/0"));
     *   // Link: </page/2>; rel="next", </page/0>; rel="prev"
     * }</pre>
     */
    Response links(Map<String, String> links);

    /**
     * Sets the {@code Location} header.
     * Returns {@code this} for chaining.
     * Mirrors Express {@code res.location(url)}.
     */
    Response location(String url);

    /**
     * {@code true} if response headers have already been sent to the client.
     * Once {@code true}, calling any send method throws {@link IllegalStateException}.
     * Mirrors Express {@code res.headersSent}.
     */
    boolean headersSent();

    // ── Cookies ───────────────────────────────────────────────────────────────

    /**
     * Sets a cookie with the given name and value.
     * Mirrors Express {@code res.cookie(name, value)}.
     */
    Response cookie(String name, String value);

    /**
     * Sets a cookie with options.
     * Mirrors Express {@code res.cookie(name, value, options)}.
     */
    Response cookie(String name, String value, CookieOptions options);

    /**
     * Clears a cookie by setting its expiry to the past.
     * Mirrors Express {@code res.clearCookie(name)}.
     */
    Response clearCookie(String name);

    /**
     * Clears a cookie with options (domain, path matching must match the set call).
     * Mirrors Express {@code res.clearCookie(name, options)}.
     */
    Response clearCookie(String name, CookieOptions options);

    // ── Redirects ─────────────────────────────────────────────────────────────

    /**
     * Redirects to the given URL with status {@code 302}.
     * Terminates the response.
     * Mirrors Express {@code res.redirect(url)}.
     */
    void redirect(String url);

    /**
     * Redirects with an explicit status code.
     * Terminates the response.
     * Mirrors Express {@code res.redirect(status, url)}.
     */
    void redirect(int status, String url);

    // ── Content Negotiation ───────────────────────────────────────────────────

    /**
     * Performs content-type negotiation and dispatches to the matching handler.
     * Returns 406 Not Acceptable if no type in the map matches {@code Accept}.
     * Mirrors Express {@code res.format(object)} — uses {@code ContentMap} not JS object literal.
     *
     * <pre>{@code
     *   res.format(ContentMap.of()
     *       .text(() -> res.send("plain text"))
     *       .html(() -> res.send("<b>bold</b>"))
     *       .json(() -> res.json(Map.of("msg", "hello")))
     *       .build());
     * }</pre>
     */
    void format(ContentMap contentMap);

    // ── Rendering ─────────────────────────────────────────────────────────────

    /**
     * Renders the named view using the registered template engine,
     * merging {@code res.locals()}, {@code app.locals()}, and the provided locals.
     * Mirrors Express {@code res.render(view, locals)}.
     */
    void render(String view, Map<String, Object> locals);

    /**
     * Renders the named view using only {@code res.locals()} and {@code app.locals()}.
     * Mirrors Express {@code res.render(view)}.
     */
    void render(String view);

    // ── File Responses ────────────────────────────────────────────────────────

    /**
     * Sends the file at the given path as a download attachment.
     * Sets {@code Content-Disposition: attachment}.
     * Terminates the response.
     * Mirrors Express {@code res.download(path)} — uses {@code java.nio.file.Path}.
     */
    void download(Path file);

    /**
     * Sends the file as a download with a custom filename.
     * Mirrors Express {@code res.download(path, filename)}.
     */
    void download(Path file, String filename);

    /**
     * Sets {@code Content-Disposition: attachment} with optional filename.
     * Does NOT send a file — use with {@link #sendFile(Path)} to serve a file inline.
     * Returns {@code this} for chaining.
     * Mirrors Express {@code res.attachment([filename])}.
     */
    Response attachment(String filename);

    /**
     * Sends the file at the given path inline (not as a download).
     * Terminates the response.
     * Mirrors Express {@code res.sendFile(path)} — uses {@code java.nio.file.Path}.
     */
    void sendFile(Path file);

    // ── Request-Scoped Locals ─────────────────────────────────────────────────

    /**
     * Sets a request-scoped local value — accessible within this request's
     * middleware chain and route handler only.
     *
     * <p>Backed by Java 21 {@code ScopedValue} for virtual-thread safety.
     * Mirrors Express {@code res.locals.key = value}.
     *
     * <pre>{@code
     *   // In middleware:
     *   res.local("user", authenticatedUser);
     *
     *   // In handler:
     *   User user = res.local("user", User.class);
     * }</pre>
     */
    Response local(String key, Object value);

    /** Typed retrieval of a request-scoped local. */
    <T> T local(String key, Class<T> type);

    /** Untyped retrieval of a request-scoped local. Returns {@code null} if absent. */
    Object local(String key);

    // ── CafeAI Streaming Extensions ───────────────────────────────────────────

    /**
     * Streams tokens from a {@link java.util.concurrent.Flow.Publisher} as
     * Server-Sent Events (SSE).
     *
     * <p>Automatically sets:
     * <ul>
     *   <li>{@code Content-Type: text/event-stream}</li>
     *   <li>{@code Cache-Control: no-cache}</li>
     *   <li>{@code Connection: keep-alive}</li>
     * </ul>
     *
     * <p>Each token is emitted as {@code data: <token>\n\n}.
     * A {@code data: [DONE]\n\n} sentinel is sent on stream completion.
     * On client disconnect the upstream publisher is cancelled.
     *
     * <p>This method is terminal — calling any other send method after
     * {@code stream()} throws {@link IllegalStateException}.
     *
     * <pre>{@code
     *   app.post("/chat", (req, res, next) ->
     *       res.stream(app.prompt(req.body("message"))));
     * }</pre>
     *
     * @param tokens a reactive publisher of string tokens
     */
    void stream(Flow.Publisher<String> tokens);

    // ── Paired Request ────────────────────────────────────────────────────────

    /**
     * Returns the paired request object for this response.
     * Mirrors Express {@code res.req} — named {@code request()} for Java clarity.
     */
    Request request();
}
