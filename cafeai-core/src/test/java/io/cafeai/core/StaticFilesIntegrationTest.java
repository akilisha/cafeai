package io.cafeai.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CafeAI.serveStatic(...)} end to end: real files, a real server, real HTTP. Covers what it
 * serves and refuses, its caching headers and conditional requests, and byte ranges
 * ({@code StaticOptions.acceptRanges}).
 */
@DisplayName("static files")
class StaticFilesIntegrationTest {

    @TempDir Path base;

    private Path pub;
    private CafeAI app;
    private int port;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private static final String TEN = "0123456789";

    @BeforeEach
    void files() throws IOException {
        pub = Files.createDirectories(base.resolve("public"));
        Files.writeString(pub.resolve("hello.txt"), TEN);
        Files.writeString(pub.resolve("index.html"), "<h1>root</h1>");
        Files.writeString(pub.resolve("page.html"), "the page");
        Files.writeString(pub.resolve(".secret"), "hidden");
        Files.createDirectories(pub.resolve("sub"));
        Files.writeString(pub.resolve("sub").resolve("index.html"), "<h1>sub</h1>");
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        Files.write(pub.resolve("bin.dat"), all);
        Files.writeString(base.resolve("outside.txt"), "OUTSIDE SECRET");
    }

    @AfterEach
    void stop() {
        if (app != null) app.stop();
    }

    private void serve(StaticOptions options) throws Exception {
        app = CafeAI.create();
        app.use(CafeAI.serveStatic(pub.toString(), options));
        app.use((req, res, next) -> res.status(404).send("fell through"));
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).as("server started").isTrue();
    }

    private HttpResponse<byte[]> send(String method, String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10));
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        // Bounded: HttpRequest.timeout covers the headers, not a body that never finishes.
        return http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray()).get(15, TimeUnit.SECONDS);
    }

    private HttpResponse<byte[]> get(String path, String... headers) throws Exception {
        return send("GET", path, headers);
    }

    private static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), java.nio.charset.StandardCharsets.UTF_8);
    }

    // -- what it serves and refuses ---------------------------------------------------------------------

    @Nested @DisplayName("serving")
    class Serving {
        @Test @DisplayName("a file is served with its content type")
        void file() throws Exception {
            serve(StaticOptions.defaults());

            var r = get("/hello.txt");

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(text(r)).isEqualTo(TEN);
            assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        }

        @Test @DisplayName("HEAD returns the headers and the length, and no body")
        void head() throws Exception {
            serve(StaticOptions.defaults());

            var r = send("HEAD", "/hello.txt");

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).isEmpty();
            assertThat(r.headers().firstValue("Content-Length")).hasValue("10");
        }

        @Test @DisplayName("a missing file falls through to the next handler")
        void fallthrough() throws Exception {
            serve(StaticOptions.defaults());

            assertThat(text(get("/nope.txt"))).isEqualTo("fell through");
        }

        @Test @DisplayName("with fallthrough(false) a missing file is a 404 from the static handler itself")
        void noFallthrough() throws Exception {
            serve(StaticOptions.builder().fallthrough(false).build());

            var r = get("/nope.txt");

            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(text(r)).isEqualTo("Not Found");
        }

        @Test @DisplayName("only GET and HEAD are served: a POST goes on to the next handler")
        void post() throws Exception {
            serve(StaticOptions.defaults());

            assertThat(text(send("POST", "/hello.txt"))).isEqualTo("fell through");
        }

        @Test @DisplayName("a directory URL without a trailing slash redirects, and the slash serves its index")
        void directoryIndex() throws Exception {
            serve(StaticOptions.defaults());

            var redirect = get("/sub");
            assertThat(redirect.statusCode()).isBetween(300, 399);
            assertThat(redirect.headers().firstValue("Location").orElse("")).endsWith("/sub/");
            assertThat(text(get("/sub/"))).isEqualTo("<h1>sub</h1>");
            assertThat(text(get("/"))).isEqualTo("<h1>root</h1>");
        }

        @Test @DisplayName("extensions() lets /page serve page.html")
        void extensions() throws Exception {
            serve(StaticOptions.builder().extensions(java.util.List.of("html")).build());

            assertThat(text(get("/page"))).isEqualTo("the page");
        }
    }

    // -- what it must never serve ------------------------------------------------------------------------------

    @Nested @DisplayName("refusals")
    class Refusals {
        @Test @DisplayName("dotfiles are ignored by default: the request goes on, the file is not served")
        void dotfilesIgnored() throws Exception {
            serve(StaticOptions.defaults());

            var r = get("/.secret");

            assertThat(text(r)).isEqualTo("fell through");
            assertThat(text(r)).doesNotContain("hidden");
        }

        @Test @DisplayName("Dotfiles.DENY answers 403")
        void dotfilesDenied() throws Exception {
            serve(StaticOptions.builder().dotfiles(StaticOptions.Dotfiles.DENY).build());

            assertThat(get("/.secret").statusCode()).isEqualTo(403);
        }

        @Test @DisplayName("Dotfiles.ALLOW serves them")
        void dotfilesAllowed() throws Exception {
            serve(StaticOptions.builder().dotfiles(StaticOptions.Dotfiles.ALLOW).build());

            assertThat(text(get("/.secret"))).isEqualTo("hidden");
        }

        @Test @DisplayName("a path that climbs out of the root never serves the file outside it")
        void traversal() throws Exception {
            serve(StaticOptions.defaults());

            for (String path : new String[] { "/%2e%2e/outside.txt", "/sub/%2e%2e/%2e%2e/outside.txt",
                                              "/..%2foutside.txt", "/%2e%2e%2foutside.txt" }) {
                var r = get(path);
                assertThat(text(r)).as(path).doesNotContain("OUTSIDE SECRET");
                assertThat(r.statusCode()).as(path).isNotEqualTo(200);
            }
        }
    }

    // -- caching --------------------------------------------------------------------------------------------------

    @Nested @DisplayName("caching")
    class Caching {
        @Test @DisplayName("by default: ETag, Last-Modified and 'public, max-age=0'")
        void defaults() throws Exception {
            serve(StaticOptions.defaults());

            var r = get("/hello.txt");

            assertThat(r.headers().firstValue("ETag")).isPresent();
            assertThat(r.headers().firstValue("Last-Modified")).isPresent();
            assertThat(r.headers().firstValue("Cache-Control")).hasValue("public, max-age=0");
        }

        @Test @DisplayName("maxAge and immutable shape Cache-Control")
        void maxAge() throws Exception {
            serve(StaticOptions.builder().maxAge(Duration.ofDays(7)).immutable(true).build());

            assertThat(get("/hello.txt").headers().firstValue("Cache-Control"))
                .hasValue("public, max-age=604800, immutable");
        }

        @Test @DisplayName("etag(false), lastModified(false) and cacheControl(false) each remove their header")
        void switchedOff() throws Exception {
            serve(StaticOptions.builder().etag(false).lastModified(false).cacheControl(false).build());

            var h = get("/hello.txt").headers();

            assertThat(h.firstValue("ETag")).isEmpty();
            assertThat(h.firstValue("Last-Modified")).isEmpty();
            assertThat(h.firstValue("Cache-Control")).isEmpty();
        }

        @Test @DisplayName("a matching If-None-Match is a 304 with no body")
        void conditional() throws Exception {
            serve(StaticOptions.defaults());
            String etag = get("/hello.txt").headers().firstValue("ETag").orElseThrow();

            var r = get("/hello.txt", "If-None-Match", etag);

            assertThat(r.statusCode()).isEqualTo(304);
            assertThat(r.body()).isEmpty();
        }

        @Test @DisplayName("a matching If-Modified-Since is a 304")
        void conditionalDate() throws Exception {
            serve(StaticOptions.defaults());
            String modified = get("/hello.txt").headers().firstValue("Last-Modified").orElseThrow();

            assertThat(get("/hello.txt", "If-Modified-Since", modified).statusCode()).isEqualTo(304);
        }

        @Test @DisplayName("a stale validator gets the file")
        void staleValidator() throws Exception {
            serve(StaticOptions.defaults());

            var r = get("/hello.txt", "If-None-Match", "\"not-the-etag\"");

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(text(r)).isEqualTo(TEN);
        }
    }

    // -- byte ranges ----------------------------------------------------------------------------------------------

    @Nested @DisplayName("byte ranges")
    class Ranges {
        @BeforeEach
        void serveDefaults() throws Exception {
            serve(StaticOptions.defaults());
        }

        @Test @DisplayName("the server advertises Accept-Ranges: bytes")
        void advertised() throws Exception {
            assertThat(get("/hello.txt").headers().firstValue("Accept-Ranges")).hasValue("bytes");
        }

        @Test @DisplayName("bytes=0-3 is a 206 with exactly those bytes and a Content-Range")
        void firstBytes() throws Exception {
            var r = get("/hello.txt", "Range", "bytes=0-3");

            assertThat(r.statusCode()).isEqualTo(206);
            assertThat(text(r)).isEqualTo("0123");
            assertThat(r.headers().firstValue("Content-Range")).hasValue("bytes 0-3/10");
            assertThat(r.headers().firstValue("Content-Length")).hasValue("4");
        }

        @Test @DisplayName("an open-ended range runs to the end of the file")
        void openEnded() throws Exception {
            var r = get("/hello.txt", "Range", "bytes=6-");

            assertThat(r.statusCode()).isEqualTo(206);
            assertThat(text(r)).isEqualTo("6789");
            assertThat(r.headers().firstValue("Content-Range")).hasValue("bytes 6-9/10");
        }

        @Test @DisplayName("a suffix range is the last N bytes")
        void suffix() throws Exception {
            var r = get("/hello.txt", "Range", "bytes=-4");

            assertThat(text(r)).isEqualTo("6789");
            assertThat(r.headers().firstValue("Content-Range")).hasValue("bytes 6-9/10");
        }

        @Test @DisplayName("an end beyond the file is clamped to its last byte")
        void clamped() throws Exception {
            var r = get("/hello.txt", "Range", "bytes=5-999");

            assertThat(r.statusCode()).isEqualTo(206);
            assertThat(text(r)).isEqualTo("56789");
            assertThat(r.headers().firstValue("Content-Range")).hasValue("bytes 5-9/10");
        }

        @Test @DisplayName("a suffix longer than the file is the whole file, as a 206")
        void oversizedSuffix() throws Exception {
            var r = get("/hello.txt", "Range", "bytes=-100");

            assertThat(r.statusCode()).isEqualTo(206);
            assertThat(text(r)).isEqualTo(TEN);
            assertThat(r.headers().firstValue("Content-Range")).hasValue("bytes 0-9/10");
        }

        @Test @DisplayName("a range that starts past the end is a 416 that says how long the file is")
        void unsatisfiable() throws Exception {
            var r = get("/hello.txt", "Range", "bytes=100-200");

            assertThat(r.statusCode()).isEqualTo(416);
            assertThat(r.headers().firstValue("Content-Range")).hasValue("bytes */10");
        }

        @Test @DisplayName("binary content is sliced exactly")
        void binary() throws Exception {
            var r = get("/bin.dat", "Range", "bytes=250-255");

            assertThat(r.statusCode()).isEqualTo(206);
            assertThat(r.body()).containsExactly((byte) 250, (byte) 251, (byte) 252, (byte) 253, (byte) 254, (byte) 255);
            var middle = get("/bin.dat", "Range", "bytes=100-103");
            assertThat(middle.body()).containsExactly((byte) 100, (byte) 101, (byte) 102, (byte) 103);
        }

        @Test @DisplayName("several ranges, another unit, or a malformed spec are ignored: the whole file, 200")
        void ignoredForms() throws Exception {
            for (String range : new String[] { "bytes=0-1,5-6", "items=0-3", "bytes=abc", "bytes=5-2", "bytes=-", "bytes=" }) {
                var r = get("/hello.txt", "Range", range);
                assertThat(r.statusCode()).as(range).isEqualTo(200);
                assertThat(text(r)).as(range).isEqualTo(TEN);
            }
        }

        @Test @DisplayName("If-Range with the current ETag keeps the range; a stale one sends the whole file")
        void ifRange() throws Exception {
            String etag = get("/hello.txt").headers().firstValue("ETag").orElseThrow();

            var current = get("/hello.txt", "Range", "bytes=0-1", "If-Range", etag);
            var stale = get("/hello.txt", "Range", "bytes=0-1", "If-Range", "\"an-older-version\"");

            assertThat(current.statusCode()).isEqualTo(206);
            assertThat(text(current)).isEqualTo("01");
            assertThat(stale.statusCode()).isEqualTo(200);
            assertThat(text(stale)).isEqualTo(TEN);
        }

        @Test @DisplayName("HEAD advertises ranges but never sends a partial body")
        void head() throws Exception {
            var r = send("HEAD", "/hello.txt", "Range", "bytes=0-3");

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).isEmpty();
            assertThat(r.headers().firstValue("Accept-Ranges")).hasValue("bytes");
        }
    }

    @Test @DisplayName("acceptRanges(false): no Accept-Ranges header, and a Range header is ignored")
    void rangesOff() throws Exception {
        serve(StaticOptions.builder().acceptRanges(false).build());

        var r = get("/hello.txt", "Range", "bytes=0-3");

        assertThat(r.headers().firstValue("Accept-Ranges")).isEmpty();
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(text(r)).isEqualTo(TEN);
        assertThat(Arrays.equals(r.body(), TEN.getBytes())).isTrue();
    }

    // -- streaming: a file is copied from disk in blocks, not held in memory ---------------------------

    @Nested @DisplayName("large files")
    class LargeFiles {

        /** Not a multiple of the 64 KiB block, so the last block is short. */
        private static final int SIZE = 9 * 1024 * 1024 + 12_345;
        private byte[] content;

        @BeforeEach
        void bigFile() throws Exception {
            content = new byte[SIZE];
            new java.util.Random(42).nextBytes(content);
            Files.write(pub.resolve("big.bin"), content);
            Files.write(pub.resolve("empty.bin"), new byte[0]);
            serve(StaticOptions.defaults());
        }

        @Test @DisplayName("the whole file arrives intact, with its Content-Length")
        void whole() throws Exception {
            var r = get("/big.bin");

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.headers().firstValue("Content-Length")).hasValue(String.valueOf(SIZE));
            assertThat(Arrays.equals(r.body(), content)).isTrue();
        }

        @Test @DisplayName("a range across several blocks is sliced exactly")
        void rangeAcrossBlocks() throws Exception {
            int start = 65_530, end = 3 * 65_536 + 9;
            var r = get("/big.bin", "Range", "bytes=" + start + "-" + end);

            assertThat(r.statusCode()).isEqualTo(206);
            assertThat(r.headers().firstValue("Content-Length")).hasValue(String.valueOf(end - start + 1));
            assertThat(Arrays.equals(r.body(), Arrays.copyOfRange(content, start, end + 1))).isTrue();
        }

        @Test @DisplayName("an open-ended range from the middle runs to the last byte")
        void openEndedFromMiddle() throws Exception {
            int start = SIZE - 200_000;
            var r = get("/big.bin", "Range", "bytes=" + start + "-");

            assertThat(r.statusCode()).isEqualTo(206);
            assertThat(Arrays.equals(r.body(), Arrays.copyOfRange(content, start, SIZE))).isTrue();
        }

        @Test @DisplayName("an empty file is a 200 with no body")
        void empty() throws Exception {
            var r = get("/empty.bin");

            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).isEmpty();
        }
    }

    // -- res.sendFile / res.download use the same streaming ---------------------------------------------

    @Test @DisplayName("res.sendFile streams a file, res.download adds Content-Disposition, a missing file is a 404")
    void sendFileAndDownload() throws Exception {
        byte[] content = new byte[300_000];
        new java.util.Random(7).nextBytes(content);
        Path file = pub.resolve("report.bin");
        Files.write(file, content);

        app = CafeAI.create();
        app.get("/file", (req, res, next) -> res.sendFile(file));
        app.get("/download", (req, res, next) -> res.download(file, "r.bin"));
        app.get("/missing", (req, res, next) -> res.sendFile(pub.resolve("nope.bin")));
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        var started = new CountDownLatch(1);
        app.listen(port, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        var f = get("/file");
        assertThat(f.statusCode()).isEqualTo(200);
        assertThat(f.headers().firstValue("Content-Length")).hasValue("300000");
        assertThat(Arrays.equals(f.body(), content)).isTrue();

        var d = get("/download");
        assertThat(d.headers().firstValue("Content-Disposition")).hasValue("attachment; filename=\"r.bin\"");
        assertThat(Arrays.equals(d.body(), content)).isTrue();

        assertThat(get("/missing").statusCode()).isEqualTo(404);
    }
}
