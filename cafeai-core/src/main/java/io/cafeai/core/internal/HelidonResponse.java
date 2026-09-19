package io.cafeai.core.internal;

import io.cafeai.core.config.AppConfig;
import io.cafeai.core.CafeAI;
import io.cafeai.core.routing.ContentMap;
import io.cafeai.core.routing.CookieOptions;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import io.helidon.http.HeaderNames;
import io.helidon.http.HeaderValues;
import io.helidon.http.Status;
import io.helidon.webserver.http.ServerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapts a Helidon {@link ServerResponse} to the CafeAI {@link Response} interface.
 * Package-private -- never referenced directly by application code.
 *
 * <p>Helidon 4.x API notes:
 * <ul>
 *   <li>Response headers use Header / HeaderValues objects objects, not raw strings</li>
 *   <li>Simplest form: {@link HeaderValues#create(String, String)} for custom headers</li>
 *   <li>Header lookup: {@code headers().value(HeaderNames.create(lc, name))}</li>
 *   <li>{@code Header.value()} is deprecated since 4.0.0 -- use {@code Header.get()} instead</li>
 * </ul>
 */
public final class HelidonResponse implements Response {

    private static final Logger log = LoggerFactory.getLogger(HelidonResponse.class);

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    /** RFC 9110 IMF-fixdate, e.g. {@code Tue, 01 Jan 2030 00:00:00 GMT} (two-digit day). */
    private static final java.time.format.DateTimeFormatter HTTP_DATE =
        java.time.format.DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.ENGLISH)
            .withZone(java.time.ZoneOffset.UTC);

    private final ServerResponse helidonRes;
    private final Map<String, Object> locals = new ConcurrentHashMap<>();

    private Request pairedRequest;
    private CafeAI  app;
    private boolean committed = false;

    /**
     * True once the body is being written to Helidon's output stream (a streamed file, SSE).
     * Helidon does not treat such a response as "sent" when a filter returns, so the filter
     * wrappers in {@code CafeAIApp} check this and tell Helidon the filter is finished.
     */
    private boolean streaming = false;

    public HelidonResponse(ServerResponse helidonRes) {
        this.helidonRes = helidonRes;
    }

    boolean isStreaming() { return streaming; }

    void setPairedRequest(Request req) { this.pairedRequest = req; }
    void setApp(CafeAI app)            { this.app = app; }

    /** The status code currently set on the response (Helidon defaults it to 200). */
    int statusCode() { return helidonRes.status().code(); }

    // -- Application Reference -------------------------------------------------

    @Override
    public CafeAI app() { return app; }

    // -- Status ----------------------------------------------------------------

    @Override
    public Response status(int code) {
        helidonRes.status(Status.create(code));
        return this;
    }

    @Override
    public void sendStatus(int code) {
        assertNotCommitted();
        status(code);
        // 204 No Content and 304 Not Modified must have no body -- HTTP spec.
        // All other codes send the reason phrase as a plain-text body.
        if (code == 204 || code == 304) {
            commit();
            helidonRes.send();
        } else {
            String reason = Status.create(code).reasonPhrase();
            setHeader("Content-Type", "text/plain");
            commit();
            helidonRes.send(reason);
        }
    }

    // -- Body Senders ----------------------------------------------------------

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

    // -- Headers ---------------------------------------------------------------

    /**
     * Internal helper: set a response header using Helidon 4's Header object API.
     *
     * <p>Uses {@link HeaderValues#create(String, String)} which is the simplest
     * correct form -- takes plain String name and value, returns a {@link Header}
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

    // -- Cookies ---------------------------------------------------------------

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
        if (options.expires() != null) {
            sb.append("; Expires=").append(HTTP_DATE.format(options.expires()));
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

    // -- Redirects -------------------------------------------------------------

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

    // -- Content Negotiation ---------------------------------------------------

    @Override
    public void format(ContentMap contentMap) {
        String accept = pairedRequest != null ? pairedRequest.header("Accept") : null;
        var handlers = contentMap.handlers();
        String chosen = Negotiation.media(accept, handlers.keySet().toArray(String[]::new));
        if (chosen == null) {
            sendStatus(406);
            return;
        }
        type(chosen);
        handlers.get(chosen).run();
    }

    // -- Rendering -------------------------------------------------------------

    @Override
    public void render(String view, Map<String, Object> viewLocals) {
        if (app == null) {
            throw new IllegalStateException(
                "res.render() needs the response to be paired with its app, which "
                + "CafeAI does when it dispatches a request");
        }
        // Precedence, lowest to highest: app.locals() < res.locals() < viewLocals.
        // The app's renderer layers app.locals() underneath what we pass.
        Map<String, Object> merged = new LinkedHashMap<>(locals);
        if (viewLocals != null) merged.putAll(viewLocals);

        var html    = new AtomicReference<String>();
        var failure = new AtomicReference<Throwable>();
        app.render(view, merged, (err, out) -> { failure.set(err); html.set(out); });

        if (failure.get() instanceof RuntimeException re) throw re;
        if (failure.get() != null) throw new RuntimeException(failure.get());
        send(html.get());   // send() defaults the Content-Type to text/html
    }

    @Override
    public void render(String view) {
        render(view, Map.of());
    }

    // -- File Responses --------------------------------------------------------

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

    private static volatile int fileBlock;

    /** Bytes copied per write when a file is streamed: {@link Response#FILE_BLOCK}, read once. */
    private static int fileBlock() {
        int block = fileBlock;
        if (block == 0) {
            block = AppConfig.load().get(Response.FILE_BLOCK);
            if (block < 1) {
                throw new IllegalArgumentException(
                    "Invalid value for " + Response.FILE_BLOCK.name() + ": " + block + " (must be positive)");
            }
            fileBlock = block;
        }
        return block;
    }

    @Override
    public void sendFile(Path file) {
        assertNotCommitted();
        if (!Files.isRegularFile(file)) {
            status(404).send("Not Found: " + file.getFileName());
            return;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            status(404).send("Not Found: " + file.getFileName());
            return;
        }
        streamFile(file, 0, size);
    }

    @Override
    public void sendFile(Path file, long offset, long length) {
        assertNotCommitted();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            status(404).send("Not Found: " + file.getFileName());
            return;
        }
        if (offset < 0 || length < 0 || offset + length > size) {
            throw new IllegalArgumentException(
                "Range " + offset + "+" + length + " is outside " + file.getFileName()
                + " (" + size + " bytes)");
        }
        streamFile(file, offset, length);
    }

    /**
     * Writes {@code length} bytes from {@code offset} in {@code FILE_BLOCK} pieces.
     * The file is opened before the response is committed, so a file that cannot be read
     * still gets a 404. Once bytes are on the wire an error can only end the connection.
     */
    private void streamFile(Path file, long offset, long length) {
        try (java.nio.channels.FileChannel in =
                 java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            commit();
            if (length == 0) {
                helidonRes.send();
                return;
            }
            helidonRes.contentLength(length);
            streaming = true;
            try (java.io.OutputStream out = helidonRes.outputStream()) {
                java.nio.ByteBuffer block = java.nio.ByteBuffer.allocate((int) Math.min(fileBlock(), Math.max(length, 1)));
                long position = offset;
                long remaining = length;
                while (remaining > 0) {
                    block.clear();
                    if (remaining < block.capacity()) block.limit((int) remaining);
                    int read = in.read(block, position);
                    if (read < 0) break;
                    out.write(block.array(), 0, read);
                    position += read;
                    remaining -= read;
                }
            } catch (IOException e) {
                // Client went away, or the file shrank under us: the connection is all that is left to end.
                log.debug("Streaming {} stopped early: {}", file.getFileName(), e.getMessage());
            }
        } catch (IOException e) {
            if (committed) {
                log.debug("Streaming {} ended with an error: {}", file.getFileName(), e.getMessage());
            } else {
                status(404).send("Not Found: " + file.getFileName());
            }
        }
    }

    // -- Request-Scoped Locals -------------------------------------------------

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

    // -- SSE Streaming ---------------------------------------------------------

    @Override
    public void stream(Flow.Publisher<String> tokens) {
        assertNotCommitted();
        setHeader("Content-Type",  "text/event-stream");
        setHeader("Cache-Control", "no-cache");
        setHeader("Connection",    "keep-alive");
        commit();

        // Helidon SE has a blocking, virtual-thread request model: the handler must
        // hold the request thread until the response is finished. So open the
        // output stream here (on the request thread) and block until the publisher
        // completes, writing SSE frames as tokens arrive. A blocked virtual thread
        // is cheap — this is the idiomatic Helidon SE streaming shape.
        streaming = true;
        final java.io.OutputStream out = helidonRes.outputStream();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

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
                    out.write(("data: " + token + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    subscription.cancel();
                    done.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
                try {
                    out.write("data: [ERROR]\n\n".getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                } finally {
                    done.countDown();
                }
            }

            @Override
            public void onComplete() {
                try {
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                } finally {
                    done.countDown();
                }
            }
        });

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            out.close();
        } catch (IOException ignored) {}
    }

    // -- Paired Request --------------------------------------------------------

    @Override
    public Request request() {
        return pairedRequest;
    }

    // -- Internal -------------------------------------------------------------

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
