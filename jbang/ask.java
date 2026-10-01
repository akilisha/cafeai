///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS com.akilisha.oss:cafeai-core:0.5.1
//DEPS org.slf4j:slf4j-simple:2.0.19

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.Ollama;
import io.cafeai.core.ai.OpenAI;

import java.util.Map;

/**
 * An LLM behind an HTTP endpoint, in one file:
 *
 * <pre>
 *   jbang ask@akilisha/cafeai
 *   curl -X POST localhost:8080/ask -H 'Content-Type: application/json' -d '{"question":"Why is the sky blue?"}'
 * </pre>
 *
 * <p>Uses Anthropic if {@code ANTHROPIC_API_KEY} is set, else OpenAI if
 * {@code OPENAI_API_KEY} is set, else a local Ollama. {@code CAFEAI_MODEL}
 * picks the model id.
 */
class ask {
    public static void main(String[] args) {
        var app = CafeAI.create();
        app.ai(provider());
        app.system("You are a concise assistant. Answer in at most three sentences.");
        app.filter(CafeAI.json());

        app.post("/ask", (req, res, next) -> {
            String question = req.body("question");
            if (question == null || question.isBlank()) {
                res.status(400).json(Map.of("error", "send {\"question\": \"...\"}"));
                return;
            }
            var answer = app.prompt(question).call();
            res.json(Map.of("answer", answer.text(), "model", answer.modelId()));
        });

        app.listen(8080, () -> System.out.println("""
            CafeAI is brewing on http://localhost:8080
              curl -X POST localhost:8080/ask -H 'Content-Type: application/json' -d '{"question":"Why is the sky blue?"}'
            """));
    }

    private static AiProvider provider() {
        String model = System.getenv("CAFEAI_MODEL");
        if (System.getenv("ANTHROPIC_API_KEY") != null) {
            return Anthropic.of(model != null ? model : "claude-haiku-4-5");
        }
        if (System.getenv("OPENAI_API_KEY") != null) {
            return OpenAI.of(model != null ? model : "gpt-4o-mini");
        }
        return Ollama.of(model != null ? model : "llama3.2");
    }
}
