package io.cafeai.dev;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("cafeai-dev reload")
class DevServerTest {

    @TempDir Path dir;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private DevServer server;
    private int port;

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private String output() {
        synchronized (bytes) { return bytes.toString(StandardCharsets.UTF_8); }
    }

    private static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private String get(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "";
        }
    }

    private void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what + "\n--- output ---\n" + output());
    }

    private Path project(String routeValue, String extra) throws Exception {
        Path src = dir.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(dir.resolve("src/main/resources/greeting.txt"), "hello");
        Path app = src.resolve("App.java");
        Files.writeString(app, appSource(routeValue, extra));
        return app;
    }

    private String appSource(String routeValue, String extra) {
        return """
                package demo;

                import io.cafeai.core.CafeAI;
                import java.util.Map;

                public class App {
                    public static void main(String[] args) throws Exception {
                        var app = CafeAI.create();
                        app.get("/v", (req, res, next) -> res.json(Map.of("v", "%s")));
                        app.get("/greeting", (req, res, next) -> {
                            try (var in = App.class.getClassLoader().getResourceAsStream("greeting.txt")) {
                                res.send(new String(in.readAllBytes()));
                            } catch (Exception e) { res.status(500).send(e.toString()); }
                        });
                        %s
                        app.listen(%d);
                    }
                }
                """.formatted(routeValue, extra, port);
    }

    private DevServer startProject() throws Exception {
        server = DevServer.project("demo.App", List.of(dir.resolve("src/main/java")),
                List.of(dir.resolve("src/main/resources")), new String[0], out).start();
        await("version 1 to answer", () -> get("/v").contains("V1"));
        return server;
    }

    @Test @DisplayName("prints what a reload does not handle, every time it starts")
    void bannerStatesTheLimitations() throws Exception {
        port = freePort();
        project("V1", "");
        startProject();
        assertThat(output())
                .contains("What a reload does NOT handle")
                .contains("Threads and executors your app starts itself")
                .contains("Static state, caches and open connections")
                .contains("needs a\n".replace("\n", System.lineSeparator()).trim())
                .contains("Never ship cafeai-dev");
    }

    @Test @DisplayName("saving a source file replaces the running app with the new code")
    void reloadsOnSave() throws Exception {
        port = freePort();
        Path app = project("V1", "");
        startProject();

        Files.writeString(app, appSource("V2", ""));
        await("version 2 to answer", () -> get("/v").contains("V2"));
        Files.writeString(app, appSource("V3", ""));
        await("version 3 to answer", () -> get("/v").contains("V3"));
        assertThat(output()).contains("Reloaded version 2").contains("Reloaded version 3");
    }

    @Test @DisplayName("a save that does not compile reports the error and keeps the previous version running")
    void compileErrorKeepsTheOldVersion() throws Exception {
        port = freePort();
        Path app = project("V1", "");
        startProject();

        Files.writeString(app, appSource("V2", "this is not java;"));
        await("the compile error", () -> output().contains("The previous version keeps running"));
        assertThat(output()).contains("error: App.java:");
        assertThat(get("/v")).contains("V1");

        Files.writeString(app, appSource("V3", ""));
        await("the fixed version to answer", () -> get("/v").contains("V3"));
    }

    @Test @DisplayName("a thread the app started itself is named after a reload, as still running old code")
    void warnsAboutLeftoverThreads() throws Exception {
        port = freePort();
        String leaky = """
                new Thread(() -> { while (true) { try { Thread.sleep(50); } catch (InterruptedException e) { } } }, "leaky-worker").start();""";
        Path app = project("V1", leaky);
        startProject();

        Files.writeString(app, appSource("V2", ""));
        await("version 2 to answer", () -> get("/v").contains("V2"));
        await("the leftover-thread warning", () -> output().contains("left 1 thread(s) running"));
        assertThat(output()).contains("leaky-worker").contains("Stop them yourself, or restart cafeai-dev");
    }

    @Test @DisplayName("a changed resource file reloads the app too")
    void reloadsResources() throws Exception {
        port = freePort();
        project("V1", "");
        startProject();
        assertThat(get("/greeting")).isEqualTo("hello");

        Files.writeString(dir.resolve("src/main/resources/greeting.txt"), "bonjour");
        await("the new resource", () -> get("/greeting").equals("bonjour"));
    }

    @Test @DisplayName("a single-file app reloads on save")
    void singleFileApp() throws Exception {
        port = freePort();
        Path script = dir.resolve("hello.java");
        Files.writeString(script, scriptSource("one", ""));
        server = DevServer.script(script, new String[0], out).start();
        await("the script to answer", () -> get("/hi").contains("one"));

        Files.writeString(script, scriptSource("two", ""));
        await("the edited script to answer", () -> get("/hi").contains("two"));
    }

    @Test @DisplayName("changing a script's //DEPS warns that it needs a restart (needs jbang)")
    void depsChangeNeedsRestart() throws Exception {
        Assumptions.assumeTrue(jbangAvailable(), "jbang is not on the PATH");
        port = freePort();
        Path script = dir.resolve("hello.java");
        Files.writeString(script, scriptSource("one", "//DEPS org.slf4j:slf4j-nop:2.0.19\n"));
        server = DevServer.script(script, new String[0], out).start();
        await("the script to answer", () -> get("/hi").contains("one"));

        Files.writeString(script, scriptSource("two", "//DEPS org.slf4j:slf4j-nop:2.0.19\n//DEPS org.slf4j:slf4j-api:2.0.19\n"));
        await("the edited script to answer", () -> get("/hi").contains("two"));
        assertThat(output()).contains("the //DEPS lines changed. Restart cafeai-dev");
    }

    private String scriptSource(String value, String deps) {
        return deps + """
                import io.cafeai.core.CafeAI;
                import java.util.Map;

                class hello {
                    public static void main(String[] args) {
                        var app = CafeAI.create();
                        app.get("/hi", (req, res, next) -> res.json(Map.of("hi", "%s")));
                        app.listen(%d);
                    }
                }
                """.formatted(value, port);
    }

    private static boolean jbangAvailable() {
        for (String exe : List.of("jbang.cmd", "jbang")) {
            try {
                return new ProcessBuilder(exe, "--version").start().waitFor() == 0;
            } catch (Exception ignored) { /* next */ }
        }
        return false;
    }
}
