package io.cafeai.core.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.cafeai.core.JsonOptions;
import io.cafeai.core.RawOptions;
import io.cafeai.core.StaticOptions;
import io.cafeai.core.TextOptions;
import io.cafeai.core.UrlEncodedOptions;
import io.cafeai.core.middleware.Middleware;

import io.helidon.http.HeaderNames;
import io.helidon.webserver.http.ServerRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Factory for CafeAI's built-in middleware implementations.
 *
 * <p><strong>Internal implementation — do not reference directly.</strong>
 * Always access via {@link io.cafeai.core.middleware.Middleware} or
 * {@link io.cafeai.core.CafeAI} factory methods.
 * Public only because Java package-private cannot cross package boundaries without JPMS.
 * JPMS module encapsulation (ROADMAP-08 Phase 3) will enforce this properly.
 */
public final class BuiltInMiddleware {

    private static final Logger log = LoggerFactory.getLogger(BuiltInMiddleware.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
        new TypeReference<>() {};

    private BuiltInMiddleware() {}

    // ── JSON Body Parser (ROADMAP-01 Phase 2) ─────────────────────────────────

    /**
     * Fully implemented JSON body parser.
     *
     * <p>Behaviour matches Express {@code express.json()} exactly:
     * <ul>
     *   <li>Only parses when {@code Content-Type} matches {@code options.type()}</li>
     *   <li>Enforces {@code options.limit()} — returns 413 on oversize</li>
     *   <li>When {@code strict=true}, rejects JSON primitives at root with 400</li>
     *   <li>Returns empty body map (not null) when Content-Type doesn't match</li>
     *   <li>Inflates gzip/deflate when {@code inflate=true}</li>
     * </ul>
     */
    public static Middleware jsonBody(JsonOptions options) {
        return (req, res, next) -> {
            String contentType = req.header("Content-Type");

            // Pass through if Content-Type doesn't match any configured type
            if (contentType == null || !matchesType(contentType, options.type())) {
                next.run();
                return;
            }

            if (!(req instanceof HelidonRequest helidonReq)) {
                next.run();
                return;
            }

            try {
                byte[] rawBytes = readBody(helidonReq.helidonServerRequest(), options.limit(),
                    options.inflate(), contentType);

                if (rawBytes == null || rawBytes.length == 0) {
                    helidonReq.setParsedBody(Map.of());
                    next.run();
                    return;
                }

                // strict=true: reject JSON primitives (strings, numbers, booleans, null)
                if (options.strict()) {
                    byte first = firstNonWhitespace(rawBytes);
                    if (first != '{' && first != '[') {
                        res.status(400).json(Map.of(
                            "error", "Strict mode: JSON body must be an object or array"));
                        return;
                    }
                }

                Map<String, Object> body = MAPPER.readValue(rawBytes, MAP_TYPE);
                helidonReq.setParsedBody(body);

            } catch (BodyTooLargeException e) {
                res.status(413).json(Map.of(
                    "error", "Payload Too Large",
                    "limit", options.limit()));
                return;
            } catch (MismatchedInputException e) {
                res.status(400).json(Map.of(
                    "error", "Invalid JSON",
                    "detail", e.getOriginalMessage()));
                return;
            } catch (IOException e) {
                log.warn("JSON body parse error: {}", e.getMessage());
                res.status(400).json(Map.of("error", "Bad Request", "detail", e.getMessage()));
                return;
            }

            next.run();
        };
    }

    /** Convenience overload used by {@link io.cafeai.core.middleware.Middleware#json()}. */
    public static Middleware json() {
        return jsonBody(JsonOptions.defaults());
    }

    // ── Raw Body Parser (ROADMAP-01 Phase 3) ──────────────────────────────────

    /**
     * Raw byte body parser. Parses body into {@code byte[]} accessible via
     * {@code req.bodyBytes()}.
     */
    public static Middleware rawBody(RawOptions options) {
        return (req, res, next) -> {
            String contentType = req.header("Content-Type");

            if (contentType == null || !matchesType(contentType, options.type())) {
                next.run();
                return;
            }

            if (!(req instanceof HelidonRequest helidonReq)) {
                next.run();
                return;
            }

            try {
                byte[] bytes = readBody(helidonReq.helidonServerRequest(),
                    options.limit(), options.inflate(), contentType);
                helidonReq.setRawBody(bytes != null ? bytes : new byte[0]);
            } catch (BodyTooLargeException e) {
                res.status(413).json(Map.of("error", "Payload Too Large",
                    "limit", options.limit()));
                return;
            } catch (IOException e) {
                log.warn("Raw body read error: {}", e.getMessage());
                res.status(400).json(Map.of("error", "Bad Request"));
                return;
            }

            next.run();
        };
    }

    // ── Text Body Parser ───────────────────────────────────────────────────────

    /**
     * Plain text body parser. Parses body into {@code String} accessible via
     * {@code req.bodyText()}.
     */
    public static Middleware textBody(TextOptions options) {
        return (req, res, next) -> {
            String contentType = req.header("Content-Type");

            if (contentType == null || !matchesType(contentType, options.type())) {
                next.run();
                return;
            }

            if (!(req instanceof HelidonRequest helidonReq)) {
                next.run();
                return;
            }

            try {
                byte[] bytes = readBody(helidonReq.helidonServerRequest(),
                    options.limit(), options.inflate(), contentType);

                // Detect charset from Content-Type header, fall back to options default
                Charset charset = detectCharset(contentType, options.defaultCharset());
                helidonReq.setTextBody(bytes != null
                    ? new String(bytes, charset)
                    : "");

            } catch (BodyTooLargeException e) {
                res.status(413).json(Map.of("error", "Payload Too Large",
                    "limit", options.limit()));
                return;
            } catch (IOException e) {
                log.warn("Text body read error: {}", e.getMessage());
                res.status(400).json(Map.of("error", "Bad Request"));
                return;
            }

            next.run();
        };
    }

    // ── CORS ──────────────────────────────────────────────────────────────────

    /**
     * Permissive CORS middleware — all origins, all methods.
     * Full options support in ROADMAP-06 Phase 3.
     */
    public static Middleware cors() {
        return (req, res, next) -> {
            res.set("Access-Control-Allow-Origin", "*");
            res.set("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,HEAD,OPTIONS");
            res.set("Access-Control-Allow-Headers",
                "Content-Type,Authorization,X-Requested-With,X-Session-Id");
            if ("OPTIONS".equalsIgnoreCase(req.method())) {
                res.set("Access-Control-Max-Age", "86400");
                res.status(204).end();
                return;
            }
            next.run();
        };
    }

    // ── Request Logger ────────────────────────────────────────────────────────

    /** Structured request/response logging. */
    public static Middleware requestLogger() {
        return (req, res, next) -> {
            long start = System.currentTimeMillis();
            try {
                next.run();
            } finally {
                long latency = System.currentTimeMillis() - start;
                log.info("{} {} ({} ms)", req.method(), req.path(), latency);
            }
        };
    }

    // ── Rate Limiter ──────────────────────────────────────────────────────────

    /** Per-IP sliding window rate limiter. */
    public static Middleware rateLimit(int requestsPerMinute) {
        final Map<String, long[]> windows = new ConcurrentHashMap<>();
        final long windowSeconds = 60L;

        return (req, res, next) -> {
            String ip = req.ip();
            long now = Instant.now().getEpochSecond();

            windows.compute(ip, (k, w) -> {
                if (w == null || now - w[0] >= windowSeconds) return new long[]{now, 1};
                w[1]++;
                return w;
            });

            long[] w = windows.get(ip);
            if (w[1] > requestsPerMinute) {
                long retryAfter = windowSeconds - (now - w[0]);
                res.set("Retry-After", String.valueOf(retryAfter));
                res.status(429).json(Map.of("error", "Too Many Requests",
                    "retryAfter", retryAfter));
                return;
            }
            next.run();
        };
    }

    // ── Token Budget ──────────────────────────────────────────────────────────

    /** Per-session LLM token budget enforcer. */
    public static Middleware tokenBudget(int maxTokensPerSession) {
        final Map<String, AtomicInteger> sessionTokens = new ConcurrentHashMap<>();

        return (req, res, next) -> {
            String sessionId = req.header("X-Session-Id");
            if (sessionId == null) { next.run(); return; }

            AtomicInteger used = sessionTokens.computeIfAbsent(
                sessionId, k -> new AtomicInteger(0));

            if (used.get() >= maxTokensPerSession) {
                res.status(429).json(Map.of("error", "Token budget exceeded",
                    "limit", maxTokensPerSession, "used", used.get()));
                return;
            }
            next.run();
        };
    }

    // ── URL-Encoded Body Parser (ROADMAP-01 Phase 7) ──────────────────────────

    /**
     * Parses {@code application/x-www-form-urlencoded} bodies into {@code req.body()}.
     *
     * <p>Both simple flat key-value pairs and (when {@code extended=true}) nested
     * bracket-notation keys ({@code a[b]=c}) are supported.
     */
    public static Middleware urlEncodedBody(UrlEncodedOptions options) {
        return (req, res, next) -> {
            String contentType = req.header("Content-Type");
            if (contentType == null
                    || !contentType.toLowerCase().contains("application/x-www-form-urlencoded")) {
                next.run();
                return;
            }

            if (!(req instanceof HelidonRequest helidonReq)) {
                next.run();
                return;
            }

            try {
                byte[] bytes = readBody(helidonReq.helidonServerRequest(),
                    options.limit(), options.inflate(), contentType);

                if (bytes == null || bytes.length == 0) {
                    helidonReq.setParsedBody(Map.of());
                    next.run();
                    return;
                }

                String encoded = new String(bytes, StandardCharsets.UTF_8);
                Map<String, Object> body = new LinkedHashMap<>();

                for (String pair : encoded.split("&")) {
                    if (pair.isBlank()) continue;
                    int eq = pair.indexOf('=');
                    String key   = eq >= 0
                        ? URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8)
                        : URLDecoder.decode(pair, StandardCharsets.UTF_8);
                    String value = eq >= 0
                        ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                        : "";

                    if (options.extended() && key.contains("[")) {
                        // nested bracket notation: a[b][c]=v → {a: {b: {c: v}}}
                        parseBracketKey(body, key, value);
                    } else {
                        // flat: multiple values for same key → List
                        body.merge(key, value, (existing, v) -> {
                            if (existing instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<Object> list = (List<Object>) existing;
                                list.add(v);
                                return list;
                            }
                            List<Object> list = new ArrayList<>();
                            list.add(existing);
                            list.add(v);
                            return list;
                        });
                    }
                }

                helidonReq.setParsedBody(Collections.unmodifiableMap(body));

            } catch (BodyTooLargeException e) {
                res.status(413).json(Map.of("error", "Payload Too Large",
                    "limit", options.limit()));
                return;
            } catch (Exception e) {
                log.warn("URL-encoded body parse error: {}", e.getMessage());
                res.status(400).json(Map.of("error", "Bad Request"));
                return;
            }

            next.run();
        };
    }

    /**
     * Parses a bracket-notation key like {@code user[name]} or {@code items[0][id]}
     * into nested maps within the body.
     */
    @SuppressWarnings("unchecked")
    private static void parseBracketKey(Map<String, Object> body, String key, String value) {
        // Extract segments: "user[name]" → ["user", "name"]
        List<String> parts = new ArrayList<>();
        int i = key.indexOf('[');
        if (i < 0) { body.put(key, value); return; }
        parts.add(key.substring(0, i));
        while (i < key.length()) {
            int j = key.indexOf(']', i);
            if (j < 0) break;
            parts.add(key.substring(i + 1, j));
            i = j + 1;
            if (i < key.length() && key.charAt(i) == '[') i++;
        }

        Map<String, Object> current = body;
        for (int k = 0; k < parts.size() - 1; k++) {
            String part = parts.get(k);
            current = (Map<String, Object>) current.computeIfAbsent(
                part, x -> new LinkedHashMap<>());
        }
        current.put(parts.get(parts.size() - 1), value);
    }

    // ── Static File Server (ROADMAP-01 Phase 5) ───────────────────────────────

    /**
     * Serves static files from {@code root} directory with full Express-parity options.
     *
     * <p>Features:
     * <ul>
     *   <li>ETag generation and conditional request support (304 Not Modified)</li>
     *   <li>Cache-Control / max-age headers</li>
     *   <li>Last-Modified header and validation</li>
     *   <li>index.html fallback for directory requests</li>
     *   <li>Extension fallback ({@code /page} → {@code /page.html})</li>
     *   <li>Dotfile protection per {@link StaticOptions.Dotfiles}</li>
     *   <li>{@code fallthrough=true} calls {@code next()} on 404 instead of responding</li>
     * </ul>
     */
    public static Middleware serveStatic(String root, StaticOptions options) {
        Path rootPath = Paths.get(root).toAbsolutePath().normalize();

        return (req, res, next) -> {
            // Only serve GET and HEAD
            String method = req.method();
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                next.run();
                return;
            }

            // Decode and normalize the request path
            String urlPath;
            try {
                urlPath = URLDecoder.decode(req.path(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                next.run();
                return;
            }

            // Security: reject path traversal attempts
            Path target = rootPath.resolve(
                urlPath.startsWith("/") ? urlPath.substring(1) : urlPath
            ).normalize();

            if (!target.startsWith(rootPath)) {
                res.status(403).send("Forbidden");
                return;
            }

            // Dotfile check on every path segment
            for (Path segment : target) {
                String name = segment.getFileName() != null
                    ? segment.getFileName().toString() : "";
                if (name.startsWith(".") && !name.equals(".")) {
                    switch (options.dotfiles()) {
                        case DENY   -> { res.status(403).send("Forbidden"); return; }
                        case IGNORE -> { if (options.fallthrough()) { next.run(); } else { res.status(404).send("Not Found"); } return; }
                        case ALLOW  -> {} // fall through to serve
                    }
                }
            }

            // Directory → index file fallback
            if (Files.isDirectory(target)) {
                if (options.redirect() && !urlPath.endsWith("/")) {
                    res.redirect(urlPath + "/");
                    return;
                }
                if (options.index() != null && !options.index().isBlank()) {
                    target = target.resolve(options.index());
                }
            }

            // Extension fallback: /page → /page.html
            if (!Files.exists(target) && !options.extensions().isEmpty()) {
                for (String ext : options.extensions()) {
                    Path candidate = Paths.get(
                        target.toString() + "." + ext);
                    if (Files.exists(candidate)) {
                        target = candidate;
                        break;
                    }
                }
            }

            // 404 — file still not found
            if (!Files.exists(target) || Files.isDirectory(target)) {
                if (options.fallthrough()) { next.run(); } else { res.status(404).send("Not Found"); }
                return;
            }

            try {
                BasicFileAttributes attrs =
                    Files.readAttributes(
                        target, BasicFileAttributes.class);

                long lastModifiedMillis = attrs.lastModifiedTime().toMillis();
                long fileSize           = attrs.size();
                String etag             = "\"" + Long.toHexString(lastModifiedMillis)
                                            + "-" + Long.toHexString(fileSize) + "\"";

                // ETag conditional request
                if (options.etag()) {
                    String ifNoneMatch = req.header("If-None-Match");
                    if (etag.equals(ifNoneMatch)) {
                        res.status(304).end();
                        return;
                    }
                    res.set("ETag", etag);
                }

                // Last-Modified conditional request
                if (options.lastModified()) {
                    ZonedDateTime lm = Instant
                        .ofEpochMilli(lastModifiedMillis)
                        .atZone(ZoneOffset.UTC);
                    String lmFormatted = DateTimeFormatter.RFC_1123_DATE_TIME.format(lm);
                    String ifModifiedSince = req.header("If-Modified-Since");
                    if (lmFormatted.equals(ifModifiedSince)) {
                        res.status(304).end();
                        return;
                    }
                    res.set("Last-Modified", lmFormatted);
                }

                // Cache-Control
                if (options.cacheControl()) {
                    long maxAgeSeconds = options.maxAge().getSeconds();
                    StringBuilder cc = new StringBuilder();
                    if (maxAgeSeconds > 0) {
                        cc.append("public, max-age=").append(maxAgeSeconds);
                        if (options.immutable()) cc.append(", immutable");
                    } else {
                        cc.append("public, max-age=0");
                    }
                    res.set("Cache-Control", cc.toString());
                }

                // Content-Type from extension
                String fileName = target.getFileName().toString();
                res.type(detectMimeType(fileName));

                // HEAD — headers only, no body
                if ("HEAD".equalsIgnoreCase(method)) {
                    res.set("Content-Length", String.valueOf(fileSize));
                    res.status(200).end();
                    return;
                }

                // GET — send file bytes
                byte[] bytes = Files.readAllBytes(target);
                res.send(bytes);

            } catch (IOException e) {
                log.warn("Static file serve error for {}: {}", target, e.getMessage());
                if (options.fallthrough()) { next.run(); } else { res.status(500).send("Internal Server Error"); }
            }
        };
    }

    /**
     * Returns a MIME type for common file extensions.
     * Falls back to {@code application/octet-stream} for unknown types.
     */
    private static String detectMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return switch (fileName.substring(dot + 1).toLowerCase()) {
            case "html", "htm"  -> "text/html; charset=utf-8";
            case "css"          -> "text/css; charset=utf-8";
            case "js", "mjs"    -> "text/javascript; charset=utf-8";
            case "json"         -> "application/json; charset=utf-8";
            case "txt"          -> "text/plain; charset=utf-8";
            case "xml"          -> "application/xml";
            case "png"          -> "image/png";
            case "jpg", "jpeg"  -> "image/jpeg";
            case "gif"          -> "image/gif";
            case "svg"          -> "image/svg+xml";
            case "ico"          -> "image/x-icon";
            case "webp"         -> "image/webp";
            case "woff"         -> "font/woff";
            case "woff2"        -> "font/woff2";
            case "ttf"          -> "font/ttf";
            case "pdf"          -> "application/pdf";
            case "zip"          -> "application/zip";
            case "mp4"          -> "video/mp4";
            case "webm"         -> "video/webm";
            case "mp3"          -> "audio/mpeg";
            case "wav"          -> "audio/wav";
            default             -> "application/octet-stream";
        };
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Reads the full request body from Helidon, enforcing a size limit and
     * decompressing if required.
     *
     * @return the raw bytes, or {@code null} if the body is empty
     * @throws BodyTooLargeException if body exceeds {@code limit}
     */
    private static byte[] readBody(ServerRequest req, long limit,
                                   boolean inflate, String contentType)
            throws IOException, BodyTooLargeException {

        InputStream raw = req.content().inputStream();

        // Decompress if Content-Encoding is gzip or deflate and inflate=true
        if (inflate) {
            String encoding = req.headers()
                .value(HeaderNames.createFromLowercase("content-encoding"))
                .orElse("");
            if ("gzip".equalsIgnoreCase(encoding)) {
                raw = new GZIPInputStream(raw);
            } else if ("deflate".equalsIgnoreCase(encoding)) {
                raw = new InflaterInputStream(raw);
            }
        }

        // Read with limit enforcement — read one byte past limit to detect oversize
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        long totalRead = 0;
        int n;
        while ((n = raw.read(buffer)) != -1) {
            totalRead += n;
            if (totalRead > limit) {
                throw new BodyTooLargeException(limit);
            }
            baos.write(buffer, 0, n);
        }
        return baos.size() > 0 ? baos.toByteArray() : null;
    }

    /**
     * Returns {@code true} if {@code contentType} matches any type in {@code types}.
     * Handles both exact match ({@code application/json}) and wildcard
     * ({@code application/*}, {@code *&#47;json}).
     */
    private static boolean matchesType(String contentType, Set<String> types) {
        // Strip parameters like "; charset=utf-8" from the content type
        String baseType = contentType.split(";")[0].trim().toLowerCase();
        for (String type : types) {
            String t = type.toLowerCase();
            if (t.equals(baseType)) return true;
            if (t.endsWith("/*") && baseType.startsWith(t.substring(0, t.length() - 1))) return true;
            if (t.startsWith("*/") && baseType.endsWith(t.substring(1))) return true;
        }
        return false;
    }

    /**
     * Returns the first non-whitespace byte from the array.
     * Used for strict JSON validation to detect primitive root values.
     */
    private static byte firstNonWhitespace(byte[] bytes) {
        for (byte b : bytes) {
            if (b != ' ' && b != '\t' && b != '\n' && b != '\r') return b;
        }
        return 0;
    }

    /**
     * Extracts charset from {@code Content-Type} header value.
     * Falls back to {@code defaultCharset} if not specified.
     * Example: {@code "text/plain; charset=ISO-8859-1"} → {@code ISO-8859-1}
     */
    private static Charset detectCharset(String contentType, Charset defaultCharset) {
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("charset=")) {
                String name = trimmed.substring("charset=".length()).trim();
                try {
                    return Charset.forName(name);
                } catch (Exception ignored) {}
            }
        }
        return defaultCharset;
    }

    /** Thrown when a request body exceeds the configured size limit. */
    static final class BodyTooLargeException extends Exception {
        final long limit;
        BodyTooLargeException(long limit) {
            super("Body exceeds limit of " + limit + " bytes");
            this.limit = limit;
        }
    }
}
