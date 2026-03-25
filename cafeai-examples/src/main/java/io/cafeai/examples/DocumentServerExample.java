package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.UploadedFile;
import io.cafeai.core.routing.WsHandler;
import io.cafeai.core.routing.WsSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * DocumentServerExample — demonstrates every body handler and WebSocket support
 * in a single CafeAI application:
 *
 * <pre>
 *   POST /upload/document     multipart/form-data   → upload a document + metadata field
 *   POST /upload/binary       application/octet-stream → upload raw binary data
 *   GET  /download/:filename  —                     → download a stored file
 *   GET  /stream/events       text/event-stream     → SSE: push live events to the browser
 *   WS   /ws/chat             WebSocket             → live bidirectional chat
 *   WS   /ws/echo             WebSocket             → echo server (one-liner demo)
 * </pre>
 *
 * <p>HTTP and WebSocket both run on the same port (8080). The Helidon runtime
 * upgrades WebSocket handshake requests automatically; all other traffic flows
 * through the normal HTTP pipeline.
 *
 * <p><strong>Try the multipart upload:</strong>
 * <pre>
 *   curl -F "document=@/path/to/file.pdf" \
 *        -F "note=My important document" \
 *        http://localhost:8080/upload/document
 * </pre>
 *
 * <p><strong>Try the binary upload:</strong>
 * <pre>
 *   curl --data-binary @/path/to/image.jpg \
 *        -H "Content-Type: application/octet-stream" \
 *        -H "X-Filename: image.jpg" \
 *        http://localhost:8080/upload/binary
 * </pre>
 *
 * <p><strong>Try SSE:</strong>
 * <pre>
 *   curl -H "Accept: text/event-stream" http://localhost:8080/stream/events
 * </pre>
 *
 * <p><strong>Try WebSocket (using wscat):</strong>
 * <pre>
 *   wscat -c ws://localhost:8080/ws/echo
 *   wscat -c ws://localhost:8080/ws/chat
 * </pre>
 */
public class DocumentServerExample {

    /** In-memory file store: filename → bytes. Replace with real storage in production. */
    private static final ConcurrentHashMap<String, byte[]> fileStore = new ConcurrentHashMap<>();

    /** Connected WebSocket chat sessions. */
    private static final Set<WsSession> chatSessions =
        ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws IOException {
        // Temp directory for saved uploads
        Path uploadDir = Files.createTempDirectory("cafeai-uploads");

        var app = CafeAI.create();

        // ── Body parsers — register the ones you need ─────────────────────────
        // Each parser is a filter: only activates for matching Content-Type.
        // They stack safely — CafeAI picks the right one per request.
        app.filter(CafeAI.json());                    // application/json
        app.filter(CafeAI.multipart());               // multipart/form-data  ← new
        app.filter(CafeAI.raw());                     // application/octet-stream
        app.filter(CafeAI.text());                    // text/plain
        app.filter(CafeAI.urlencoded());              // application/x-www-form-urlencoded

        app.filter(Middleware.requestLogger());
        app.filter(Middleware.cors());

        // ── 1. Multipart document upload ──────────────────────────────────────
        // Client sends multipart/form-data with a file field "document" and
        // an optional text field "note". CafeAI.multipart() parses both.
        app.post("/upload/document", (req, res, next) -> {
            UploadedFile doc = req.file("document");

            if (doc == null) {
                res.status(400).json(Map.of(
                    "error", "No file found in field 'document'",
                    "hint",  "Use: curl -F 'document=@/path/to/file.pdf' ..."));
                return;
            }

            // Validate file type — only PDF and common document types
            Set<String> allowed = Set.of(
                "application/pdf", "application/msword",
                "text/plain", "text/markdown",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

            if (!allowed.contains(doc.mimeType())) {
                res.status(415).json(Map.of(
                    "error",     "Unsupported file type",
                    "received",  doc.mimeType(),
                    "supported", allowed));
                return;
            }

            // Save to disk and to in-memory store for download
            Path saved;
            try {
                saved = doc.saveToDirectory(uploadDir);
            } catch (java.io.IOException e) {
                next.fail(new RuntimeException("Failed to save uploaded file: " + e.getMessage(), e));
                return;
            }
            fileStore.put(doc.originalName(), doc.bytes());

            // req.body() also has the text fields from the same form
            String note = req.body("note");

            res.status(201).json(Map.of(
                "uploaded",    doc.originalName(),
                "size",        doc.size(),
                "mimeType",    doc.mimeType(),
                "savedTo",     saved.toString(),
                "note",        note != null ? note : "",
                "downloadUrl", "/download/" + doc.originalName()
            ));
        });

        // ── 2. Binary document upload ─────────────────────────────────────────
        // Client sends raw bytes with Content-Type: application/octet-stream.
        // The original filename comes from a custom header.
        // Use for programmatic uploads where multipart overhead isn't needed.
        app.post("/upload/binary", (req, res, next) -> {
            byte[] bytes = req.bodyBytes();

            if (bytes == null || bytes.length == 0) {
                res.status(400).json(Map.of(
                    "error", "Empty body — send raw bytes with Content-Type: application/octet-stream",
                    "hint",  "Use: curl --data-binary @file.jpg -H 'Content-Type: application/octet-stream' ..."));
                return;
            }

            // Filename from header, fallback to generated name
            String filename = req.header("X-Filename");
            if (filename == null || filename.isBlank()) {
                filename = "upload-" + System.currentTimeMillis() + ".bin";
            }

            // Sanitise: strip path components, keep basename only
            filename = Path.of(filename).getFileName().toString();

            fileStore.put(filename, bytes);
            Path saved = uploadDir.resolve(filename);
            try {
                Files.write(saved, bytes);
            } catch (java.io.IOException e) {
                next.fail(new RuntimeException("Failed to save uploaded file: " + e.getMessage(), e));
                return;
            }

            res.status(201).json(Map.of(
                "uploaded",    filename,
                "bytes",       bytes.length,
                "downloadUrl", "/download/" + filename
            ));
        });

        // ── 3. Document download ──────────────────────────────────────────────
        // Serves any previously uploaded file by filename.
        // Sets Content-Disposition: attachment so browsers prompt a save dialog.
        app.get("/download/:filename", (req, res, next) -> {
            String filename = req.params("filename");
            byte[] bytes    = fileStore.get(filename);

            if (bytes == null) {
                res.status(404).json(Map.of(
                    "error",     "File not found",
                    "filename",  filename,
                    "available", fileStore.keySet()));
                return;
            }

            res.set("Content-Disposition", "attachment; filename=\"" + filename + "\"")
               .set("Content-Length",      String.valueOf(bytes.length))
               .type(detectMime(filename))
               .send(bytes);
        });

        // ── 4. SSE — Server-Sent Events ───────────────────────────────────────
        // A long-lived HTTP response that streams events to the client.
        // req.stream() returns true when Accept: text/event-stream is present.
        // SubmissionPublisher is Java's built-in Flow.Publisher.
        app.get("/stream/events", (req, res, next) -> {
            if (!req.stream()) {
                // Fallback: regular clients get a JSON explanation
                res.json(Map.of(
                    "hint", "Connect with Accept: text/event-stream for live events",
                    "curl", "curl -H 'Accept: text/event-stream' http://localhost:8080/stream/events"));
                return;
            }

            // SubmissionPublisher implements Flow.Publisher<String>
            var publisher = new SubmissionPublisher<String>();

            // res.stream() sets SSE headers and subscribes — returns immediately.
            // The publisher keeps the connection open until closed.
            res.stream(publisher);

            // Simulate a live event source: emit 5 events then close.
            // In production this would be a real event stream (DB changes, queue, etc.)
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 1; i <= 5; i++) {
                        publisher.submit("event " + i + " at " + System.currentTimeMillis());
                        Thread.sleep(500);
                    }
                    publisher.submit("[DONE]");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    publisher.close();
                }
            });
        });

        // ── WebSocket: Chat room ──────────────────────────────────────────────
        // Multiple clients connect. Every message is broadcast to all others.
        // HTTP and WebSocket share the same port — no configuration needed.
        app.ws("/ws/chat", new WsHandler() {

            @Override
            public void onOpen(WsSession session) {
                chatSessions.add(session);
                broadcast(session, "system", session.id() + " joined");
                session.send("Welcome! You are: " + session.id()
                    + " | " + chatSessions.size() + " online");
            }

            @Override
            public void onMessage(WsSession session, String message) {
                // Broadcast to all other connected sessions
                broadcast(session, session.id(), message);
            }

            @Override
            public void onClose(WsSession session, int code, String reason) {
                chatSessions.remove(session);
                broadcast(session, "system", session.id() + " left");
            }

            @Override
            public void onError(WsSession session, Throwable error) {
                chatSessions.remove(session);
                System.err.println("WS error on " + session.id() + ": " + error.getMessage());
            }

            private void broadcast(WsSession sender, String from, String message) {
                String payload = "[" + from + "] " + message;
                chatSessions.forEach(s -> {
                    if (s != sender) {
                        try { s.send(payload); }
                        catch (Exception ignored) { chatSessions.remove(s); }
                    }
                });
            }
        });

        // ── WebSocket: Echo server (one-liner) ────────────────────────────────
        // WsHandler.onMessage() is the lambda-friendly shorthand for when
        // you only care about messages.
        app.ws("/ws/echo",
            WsHandler.onMessage((session, msg) -> session.send("Echo: " + msg)));

        // ── Serve a status page for manual testing ────────────────────────────
        app.get("/", (req, res, next) ->
            res.type("html").send("""
                <html><body>
                <h2>CafeAI Document Server</h2>
                <h3>Endpoints</h3>
                <ul>
                  <li>POST /upload/document  — multipart/form-data upload</li>
                  <li>POST /upload/binary    — raw binary upload</li>
                  <li>GET  /download/:file   — download a stored file</li>
                  <li>GET  /stream/events    — SSE stream (use curl or EventSource)</li>
                  <li>WS   /ws/chat          — multi-client WebSocket chat</li>
                  <li>WS   /ws/echo          — WebSocket echo server</li>
                </ul>
                </body></html>"""));

        // ── Start — HTTP and WebSocket on the same port ───────────────────────
        app.listen(8080, () -> System.out.println("""
            ☕ Document server running on http://localhost:8080

            Body handler routes:
              POST /upload/document    multipart/form-data
              POST /upload/binary      application/octet-stream
              GET  /download/:filename download a stored file
              GET  /stream/events      SSE (curl -H 'Accept: text/event-stream')

            WebSocket endpoints (same port):
              ws://localhost:8080/ws/chat   multi-client chat room
              ws://localhost:8080/ws/echo   echo server

            Test upload:
              curl -F 'document=@README.md' -F 'note=test file' \\
                   http://localhost:8080/upload/document

            Test WebSocket (requires wscat: npm install -g wscat):
              wscat -c ws://localhost:8080/ws/echo
            """));
    }

    private static String detectMime(String filename) {
        if (filename.endsWith(".pdf"))  return "application/pdf";
        if (filename.endsWith(".png"))  return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".txt"))  return "text/plain";
        if (filename.endsWith(".md"))   return "text/markdown";
        return "application/octet-stream";
    }
}
