package io.cafeai.core.internal;

import io.cafeai.core.CafeAI;
import io.cafeai.core.routing.*;
import io.helidon.http.HeaderNames;
import io.helidon.http.HeaderValues;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * Adapts a Helidon {@link ServerResponse} to the CafeAI {@link Response} interface.
 * Package-private — never referenced directly by application code.
 *
 * <p>Helidon 4.x API notes:
 * <ul>
 *   <li>Response headers use Header / HeaderValues objects objects, not raw strings</li>
 *   <li>Simplest form: {@link HeaderValues#create(String, String)} for custom headers</li>
 *   <li>Header lookup: {@code headers().value(HeaderNames.create(lc, name))}</li>
 *   <li>{@link Header#value()} is deprecated since 4.0.0 — use {@code Header.get()} instead</li>
 * </ul>
 */
public final class HelidonResponse implements Response {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private final ServerResponse helidonRes;
    private final Map<String, Object> locals = new ConcurrentHashMap<>();

    private Request pairedRequest;
    private CafeAI  app;
    private boolean committed = false;

    public HelidonResponse(ServerResponse helidonRes) {
        this.helidonRes = helidonRes;
    }

    void setPairedRequest(Request req) { this.pairedRequest = req; }
    void setApp(CafeAI app)            { this.app = app; }

    // ── Application Reference ─────────────────────────────────────────────────

    @Override
    public CafeAI app() { return app; }

    // ── Status ────────────────────────────────────────────────────────────────

    @Override
    public Response status(int code) {
        helidonRes.status(Status.create(code));
        return this;
    }

    @Override
    public void sendStatus(int code) {
        assertNotCommitted();
        String reason = Status.create(code).reasonPhrase();
        status(code);
        setHeader("Content-Type", "text/plain");
        commit();
        helidonRes.send(reason);
    }

    // ── Body Senders ──────────────────────────────────────────────────────────

    @Override
    public void send(String body) {
        assertNotCommitted();
        if (header("Content-Type") == null) {
            setHeader("Content-Type", "text/html; charset=utf-8");
        }
        commit();
        helidonRes.send(body);
    }

    @Override
    public void send(byte[] body) {
        assertNotCommitted();
        if (header("Content-Type") == null) {
            setHeader("Content-Type", "application/octet-stream");
        }
        commit();
        helidonRes.send(body);
    }

    @Override
    public void json(Object body) {
        assertNotCommitted();
        try {
            String json = MAPPER.writeValueAsString(body);
            setHeader("Content-Type", "application/json; charset=utf-8");
            commit();
            helidonRes.send(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize response to JSON", e);
        }
    }

    @Override
    public void end() {
        assertNotCommitted();
        commit();
        helidonRes.send();
    }

    // ── Headers ───────────────────────────────────────────────────────────────

    /**
     * Internal helper: set a response header using Helidon 4's Header object API.
     *
     * <p>Uses {@link HeaderValues#create(String, String)} which is the simplest
     * correct form — takes plain String name and value, returns a {@link Header}
     * that {@link WritableHeaders#set(Header)} accepts.
     */
    private void setHeader(String field, String value) {
        helidonRes.headers().set(HeaderValues.create(field, value));
    }

    @Override
    public Response set(String field, String value) {
        setHeader(field, value);
        return this;
    }

    @Override
    public Response set(Map<String, String> headers) {
        headers.forEach(this::setHeader);
        return this;
    }

    @Override
    public Response append(String field, String value) {
        String existing = header(field);
        setHeader(field, existing != null ? existing + ", " + value : value);
        return this;
    }

    @Override
    public String header(String field) {
        return helidonRes.headers()
            .value(HeaderNames.create(field.toLowerCase(), field))
            .orElse(null);
    }

    @Override
    public Response type(String type) {
        String mimeType = switch (type.toLowerCase()) {
            case "json"  -> "application/json; charset=utf-8";
            case "html"  -> "text/html; charset=utf-8";
            case "text"  -> "text/plain; charset=utf-8";
            case "xml"   -> "application/xml";
            case "form"  -> "application/x-www-form-urlencoded";
            case "bin"   -> "application/octet-stream";
            default      -> type;
        };
        setHeader("Content-Type", mimeType);
        return this;
    }

    @Override
    public Response vary(String field) {
        String existing = header("Vary");
        if (existing == null) {
            setHeader("Vary", field);
        } else if (!existing.contains(field)) {
            setHeader("Vary", existing + ", " + field);
        }
        return this;
    }

    @Override
    public Response links(Map<String, String> links) {
        var sb = new StringBuilder();
        links.forEach((rel, url) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append("<").append(url).append(">; rel=\"").append(rel).append("\"");
        });
        setHeader("Link", sb.toString());
        return this;
    }

    @Override
    public Response location(String url) {
        setHeader("Location", url);
        return this;
    }

    @Override
    public boolean headersSent() {
        return committed;
    }

    // ── Cookies ───────────────────────────────────────────────────────────────

    @Override
    public Response cookie(String name, String value) {
        return cookie(name, value, CookieOptions.builder().build());
    }

    @Override
    public Response cookie(String name, String value, CookieOptions options) {
        var sb = new StringBuilder();
        sb.append(name).append("=").append(value);
        if (options.maxAge() != null) {
            sb.append("; Max-Age=").append(options.maxAge().getSeconds());
        }
        if (options.domain() != null) {
            sb.append("; Domain=").append(options.domain());
        }
        sb.append("; Path=").append(options.path() != null ? options.path() : "/");
        if (options.secure())   sb.append("; Secure");
        if (options.httpOnly()) sb.append("; HttpOnly");
        if (options.sameSite() != null) {
            sb.append("; SameSite=").append(options.sameSite().name());
        }
        // Append to Set-Cookie (multiple cookies = multiple Set-Cookie headers)
        helidonRes.headers().add(HeaderValues.create("Set-Cookie", sb.toString()));
        return this;
    }

    @Override
    public Response clearCookie(String name) {
        return clearCookie(name, CookieOptions.builder().build());
    }

    @Override
    public Response clearCookie(String name, CookieOptions options) {
        return cookie(name, "", CookieOptions.builder()
            .maxAge(Duration.ZERO)
            .path(options.path() != null ? options.path() : "/")
            .build());
    }

    // ── Redirects ─────────────────────────────────────────────────────────────

    @Override
    public void redirect(String url) {
        redirect(302, url);
    }

    @Override
    public void redirect(int status, String url) {
        assertNotCommitted();
        helidonRes.status(Status.create(status));
        setHeader("Location", url);
        commit();
        helidonRes.send();
    }

    // ── Content Negotiation ───────────────────────────────────────────────────

    @Override
    public void format(ContentMap contentMap) {
        String accept = pairedRequest != null ? pairedRequest.header("Accept") : null;
        if (accept == null) accept = "*/*";
        for (var entry : contentMap.handlers().entrySet()) {
            if (accept.contains(entry.getKey()) || accept.contains("*/*")) {
                type(entry.getKey());
                entry.getValue().run();
                return;
            }
        }
        sendStatus(406);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(String view, Map<String, Object> locals) {
        throw new UnsupportedOperationException(
            "res.render() requires app.engine() registration — ROADMAP-02 Phase 8");
    }

    @Override
    public void render(String view) {
        render(view, Map.of());
    }

    // ── File Responses ────────────────────────────────────────────────────────

    @Override
    public void download(Path file) {
        download(file, file.getFileName().toString());
    }

    @Override
    public void download(Path file, String filename) {
        assertNotCommitted();
        setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        sendFile(file);
    }

    @Override
    public Response attachment(String filename) {
        setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        return this;
    }

    @Override
    public void sendFile(Path file) {
        assertNotCommitted();
        try {
            byte[] bytes = Files.readAllBytes(file);
            commit();
            helidonRes.send(bytes);
        } catch (IOException e) {
            status(404).send("Not Found: " + file.getFileName());
        }
    }

    // ── Request-Scoped Locals ─────────────────────────────────────────────────

    @Override
    public Response local(String key, Object value) {
        locals.put(key, value);
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T local(String key, Class<T> type) {
        Object value = locals.get(key);
        if (value == null) return null;
        return type.cast(value);
    }

    @Override
    public Object local(String key) {
        return locals.get(key);
    }

    // ── SSE Streaming ─────────────────────────────────────────────────────────

    @Override
    public void stream(Flow.Publisher<String> tokens) {
        assertNotCommitted();
        setHeader("Content-Type",  "text/event-stream");
        setHeader("Cache-Control", "no-cache");
        setHeader("Connection",    "keep-alive");
        commit();

        tokens.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String token) {
                try {
                    helidonRes.outputStream()
                        .write(("data: " + token + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    helidonRes.outputStream().flush();
                } catch (IOException e) {
                    subscription.cancel();
                }
            }

            @Override
            public void onError(Throwable t) {
                try {
                    helidonRes.outputStream()
                        .write("data: [ERROR]\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    helidonRes.outputStream().close();
                } catch (IOException ignored) {}
            }

            @Override
            public void onComplete() {
                try {
                    helidonRes.outputStream()
                        .write("data: [DONE]\n\n"
                            .getBytes(StandardCharsets.UTF_8));
                    helidonRes.outputStream().close();
                } catch (IOException ignored) {}
            }
        });
    }

    // ── Paired Request ────────────────────────────────────────────────────────

    @Override
    public Request request() {
        return pairedRequest;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void assertNotCommitted() {
        if (committed) {
            throw new IllegalStateException(
                "Response has already been sent. " +
                "Cannot send a second response for the same request.");
        }
    }

    private void commit() {
        committed = true;
    }
}
