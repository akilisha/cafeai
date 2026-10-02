package io.cafeai.test;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Ollama;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records real Ollama calls, then replays them with the provider pointed at a port
 * nothing listens on -- identical answers prove the replay never reached a model.
 * Covers a plain call, a streamed call, and an agent built on AiServices.
 *
 * <p>Skipped unless Ollama is running with the model pulled. Run with
 * {@code ./gradlew :cafeai-test:liveTest}.
 */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Replay — live against Ollama")
class ReplayOllamaLiveTest {

    private static final String URL = System.getenv().getOrDefault("OLLAMA_LIVE_URL", "http://localhost:11434");
    private static final String MODEL = System.getenv().getOrDefault("OLLAMA_LIVE_MODEL", "llama3.2");
    private static final String NOWHERE = "http://localhost:1";

    public interface Assistant {
        String chat(String message);
    }

    @TempDir Path dir;

    @BeforeAll
    void ollamaIsThere() {
        try {
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create(URL + "/api/tags")).timeout(Duration.ofSeconds(3)).build(),
                            HttpResponse.BodyHandlers.ofString());
            Assumptions.assumeTrue(response.statusCode() == 200 && response.body().contains("\"" + MODEL),
                    "model " + MODEL + " is not pulled at " + URL);
        } catch (Exception e) {
            Assumptions.abort("nothing is listening at " + URL);
        }
    }

    private CafeAI app(String url, ReplayMode mode) {
        AiProvider ollama = Ollama.at(url).model(MODEL);
        var app = CafeAI.create();
        app.ai(Replay.of(ollama, dir).mode(mode));
        return app;
    }

    @Test @DisplayName("a call recorded from Ollama replays with no model reachable")
    void call() {
        String recorded = app(URL, ReplayMode.AUTO).prompt("Name one primary colour. Answer with one word.").call().text();
        String replayed = app(NOWHERE, ReplayMode.REPLAY).prompt("Name one primary colour. Answer with one word.").call().text();

        assertThat(recorded).isNotBlank();
        assertThat(replayed).isEqualTo(recorded);
    }

    @Test @DisplayName("a streamed call recorded from Ollama replays as the same tokens")
    void stream() {
        List<String> recorded = new ArrayList<>();
        List<String> replayed = new ArrayList<>();
        app(URL, ReplayMode.AUTO).prompt("Count from one to five in words.").stream(recorded::add);
        app(NOWHERE, ReplayMode.REPLAY).prompt("Count from one to five in words.").stream(replayed::add);

        assertThat(recorded).hasSizeGreaterThan(1);
        assertThat(replayed).isEqualTo(recorded);
    }

    @Test @DisplayName("an agent's calls are recorded and replayed too")
    void agent() throws Exception {
        CafeAI live = app(URL, ReplayMode.AUTO);
        live.agent("helper", Assistant.class);
        String recorded = live.agent("helper", Assistant.class, null).chat("What is 2 + 2? Answer with just the number.");

        CafeAI offline = app(NOWHERE, ReplayMode.REPLAY);
        offline.agent("helper", Assistant.class);
        String replayed = offline.agent("helper", Assistant.class, null).chat("What is 2 + 2? Answer with just the number.");

        assertThat(recorded).isNotBlank();
        assertThat(replayed).isEqualTo(recorded);
        try (var files = Files.list(dir)) {
            System.out.println("cassettes: " + files.map(f -> f.getFileName().toString()).toList());
        }
    }
}
