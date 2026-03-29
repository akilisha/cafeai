package io.cafeai.observability;

import io.cafeai.core.Attributes;
import io.cafeai.core.ai.PromptResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluation harness -- automatically scores RAG responses for quality.
 *
 * <p>Register via {@code app.eval(EvalHarness.defaults())} at startup.
 * After each RAG-augmented prompt call, scores are attached to:
 * <ul>
 *   <li>{@code req.attribute(Attributes.EVAL_SCORES)} -- the full score map</li>
 *   <li>The OTel span if {@code app.observe(ObserveStrategy.otel())} is active</li>
 * </ul>
 *
 * <pre>{@code
 *   app.observe(ObserveStrategy.otel());
 *   app.eval(EvalHarness.defaults());
 *
 *   app.post("/ask", (req, res, next) -> {
 *       PromptResponse response = app.prompt(req.body("question")).call();
 *
 *       @SuppressWarnings("unchecked")
 *       Map<String, Double> scores = (Map<String, Double>)
 *           req.attribute(Attributes.EVAL_SCORES);
 *
 *       res.json(Map.of(
 *           "answer", response.text(),
 *           "faithfulness", scores.get("faithfulness"),
 *           "relevance",    scores.get("relevance")
 *       ));
 *   });
 * }</pre>
 */
public final class EvalHarness {

    private static final Logger log = LoggerFactory.getLogger(EvalHarness.class);

    /**
     * Default evaluation harness. Computes three scores for every
     * RAG-augmented response:
     *
     * <ul>
     *   <li><strong>faithfulness</strong> (0.0-1.0) -- does the answer stay
     *       within the retrieved context? High score means no hallucination
     *       relative to retrieved documents.</li>
     *   <li><strong>relevance</strong> (0.0-1.0) -- does the answer address
     *       the user's question? Measured by keyword overlap between question
     *       and answer.</li>
     *   <li><strong>groundedness</strong> (0.0-1.0) -- is the answer supported
     *       by the retrieved documents? Measured by term overlap between
     *       answer and retrieved content.</li>
     * </ul>
     *
     * <p>These are heuristic scores -- fast and zero-cost, suitable for
     * continuous monitoring. For rigorous evals, use LLM-as-judge scoring
     * in a dedicated offline eval pipeline.
     */
    public static EvalHarness defaults() {
        return new EvalHarness();
    }

    private EvalHarness() {}

    /**
     * Evaluates a RAG response and returns a map of metric name -> score.
     * Called by {@link ObserveBridgeImpl} after each prompt call.
     *
     * @param question  the user's original question
     * @param response  the LLM's response
     * @return map of metric name to score in [0.0, 1.0]
     */
    public Map<String, Double> evaluate(String question, PromptResponse response) {
        Map<String, Double> scores = new LinkedHashMap<>();

        List<Object> ragDocs = response.ragDocuments();
        String answer = response.text() != null ? response.text() : "";

        if (ragDocs == null || ragDocs.isEmpty()) {
            // No RAG context -- skip groundedness and faithfulness
            scores.put("relevance",    relevance(question, answer));
            scores.put("faithfulness", 1.0); // no context to violate
            scores.put("groundedness", 0.0); // no documents to ground in
            return scores;
        }

        String context = ragDocs.stream()
            .map(Object::toString)
            .reduce("", (a, b) -> a + " " + b);

        scores.put("faithfulness", faithfulness(answer, context));
        scores.put("relevance",    relevance(question, answer));
        scores.put("groundedness", groundedness(answer, context));
        return scores;
    }

    // -- Heuristic scorers -----------------------------------------------------

    /**
     * Faithfulness -- fraction of answer sentences that can be grounded in
     * the retrieved context. Approximated by word overlap.
     */
    private static double faithfulness(String answer, String context) {
        if (answer.isBlank() || context.isBlank()) return 1.0;
        java.util.Set<String> contextWords = words(context);
        java.util.Set<String> answerWords  = words(answer);
        if (answerWords.isEmpty()) return 1.0;
        long overlap = answerWords.stream().filter(contextWords::contains).count();
        return Math.min(1.0, (double) overlap / answerWords.size());
    }

    /**
     * Relevance -- fraction of question keywords present in the answer.
     */
    private static double relevance(String question, String answer) {
        if (question.isBlank() || answer.isBlank()) return 0.0;
        java.util.Set<String> questionWords = words(question);
        java.util.Set<String> answerWords   = words(answer);
        if (questionWords.isEmpty()) return 0.0;
        long overlap = questionWords.stream().filter(answerWords::contains).count();
        return Math.min(1.0, (double) overlap / questionWords.size());
    }

    /**
     * Groundedness -- fraction of answer content terms found in retrieved docs.
     */
    private static double groundedness(String answer, String context) {
        return faithfulness(answer, context); // same heuristic, same semantics
    }

    private static java.util.Set<String> words(String text) {
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String w : text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() > 3) result.add(w); // skip stop words by length proxy
        }
        return result;
    }
}
