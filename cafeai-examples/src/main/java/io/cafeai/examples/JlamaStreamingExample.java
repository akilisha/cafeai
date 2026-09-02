package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Jlama;
import io.cafeai.core.memory.MemoryStrategy;

import java.util.Map;

/**
 * JlamaStreamingExample — token streaming with a pure-Java local model.
 *
 * <p>No API key, no Ollama server, no native binary. Jlama pulls a small
 * quantized model from Hugging Face on first run (cached in {@code ~/.jlama})
 * and runs inference in-process. Tokens are streamed to the browser as
 * Server-Sent Events via {@code app.prompt(...).stream()}.
 *
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.JlamaStreamingExample
 *
 *   # streaming (tokens arrive live):
 *   curl -N -H 'Accept: text/event-stream' -H 'Content-Type: application/json' \
 *        -d '{"message":"Explain virtual threads in two sentences."}' \
 *        http://localhost:8080/chat
 *
 *   # non-streaming fallback:
 *   curl -H 'Content-Type: application/json' \
 *        -d '{"message":"Say hi."}' http://localhost:8080/chat
 * </pre>
 *
 * <p>Run args {@code --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED}
 * are required for Jlama and are set by the {@code cafeai-examples} build.
 */
public class JlamaStreamingExample {

    public static void main(String[] args) {
        var app = CafeAI.create();

        // Pure-Java local model — ~1B params, downloads once (~700 MB).
        app.ai(Jlama.tinyLlama());
        app.memory(MemoryStrategy.inMemory());
        app.system("You are a concise assistant. Answer in at most three sentences.");

        app.post("/chat", (req, res, next) -> {
            String message = req.body("message");
            String session = req.header("X-Session-Id");

            if (req.stream()) {
                // Tokens flow to the client as `data: <token>\n\n` SSE frames.
                res.stream(app.prompt(message).session(session));
            } else {
                res.json(Map.of("reply", app.prompt(message).session(session).call().text()));
            }
        });

        app.listen(8080, () -> System.out.println("☕ Jlama streaming on http://localhost:8080/chat"));
    }
}
