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
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An eval suite against a real model, graded by a real judge model: recorded from
 * Ollama, then replayed with both pointed at a port nothing listens on.
 *
 * <p>It does not assert that every case passes -- that is the model's business --
 * only that the judge gave real PASS/FAIL verdicts and that the replayed run
 * reaches exactly the same verdicts with no model reachable.
 *
 * <p>Skipped unless Ollama is running with the model pulled. Run with
 * {@code ./gradlew :cafeai-test:liveTest}.
 */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Evals — live against Ollama")
class EvalsOllamaLiveTest {

    private static final String URL = System.getenv().getOrDefault("OLLAMA_LIVE_URL", "http://localhost:11434");
    private static final String MODEL = System.getenv().getOrDefault("OLLAMA_LIVE_MODEL", "llama3.2");
    private static final String NOWHERE = "http://localhost:1";

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

    private EvalReport run(String url, ReplayMode mode) {
        AiProvider model = Ollama.at(url).model(MODEL).withTemperature(0);
        var app = CafeAI.create();
        app.ai(Replay.of(model, dir).mode(mode));
        app.system("You are the support assistant for Acme Outfitters. Refunds are accepted within "
                + "14 days of delivery. Shipping is free on orders over $50. Answer in one or two sentences.");

        return Evals.of(app)
                .judge(Replay.of(model, dir).mode(mode))
                .ask("How long do I have to return something?").expectContains("14")
                .ask("Is shipping free?").judgedBy("says shipping is free on orders over $50")
                .ask("What is your refund policy?").judgedBy("mentions a 14-day window")
                .ask("Can I return a jacket after two months?").judgedBy("says no, because it is past the 14-day window")
                // A control the answer must fail: a judge that passes this is rubber-stamping.
                .ask("Is shipping free on a $70 order?").judgedBy("says shipping costs $10 on every order")
                .run();
    }

    @Test @DisplayName("a recorded eval suite replays to the same verdicts with no model reachable")
    void recordAndReplay() {
        EvalReport recorded = run(URL, ReplayMode.AUTO);
        System.out.println("recorded:\n" + recorded);

        // The judge must give real verdicts: a parse failure would mean the rubric prompt does not work.
        recorded.results().stream().flatMap(r -> r.checks().stream())
                .forEach(c -> assertThat(c.detail()).doesNotStartWith("the judge gave no PASS/FAIL verdict"));

        assertThat(recorded.results().getLast().passed())
                .as("the judge failed the control case, so it is not passing everything").isFalse();

        EvalReport replayed = run(NOWHERE, ReplayMode.REPLAY);
        assertThat(replayed.results()).extracting(EvalReport.Result::passed)
                .containsExactlyElementsOf(recorded.results().stream().map(EvalReport.Result::passed).toList());
        assertThat(replayed.compare(recorded).regressed()).isEmpty();
    }
}
