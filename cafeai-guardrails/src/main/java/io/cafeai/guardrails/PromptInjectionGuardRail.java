package io.cafeai.guardrails;

import io.cafeai.core.Attributes;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt injection detection guardrail.
 *
 * <p>Detects attempts to inject malicious instructions through:
 * <ul>
 *   <li><strong>User input</strong> -- direct injection in the user's message</li>
 *   <li><strong>RAG documents</strong> -- indirect injection via retrieved content
 *       (a document in the vector store contains hidden instructions)</li>
 * </ul>
 *
 * <p>Indirect injection via RAG is particularly dangerous because the source
 * is trusted infrastructure. This guardrail inspects both paths.
 *
 * <pre>{@code
 *   app.guard(GuardRail.promptInjection());
 * }</pre>
 */
public final class PromptInjectionGuardRail extends AbstractGuardRail {

    private static final double DEFAULT_THRESHOLD = 0.65;
    private final double threshold;

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        // Classic direct injection
        Pattern.compile("ignore (all |previous |the )?(instructions?|prompt|rules?|guidelines?)",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("new (instructions?|task|objective|goal)\\s*:",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(system|assistant|ai)\\s*:\\s*",
            Pattern.CASE_INSENSITIVE),
        // Hidden instruction markers
        Pattern.compile("<!-{2,}.*?-{2,}>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("\\[SYSTEM\\]|\\[INST\\]|\\[OVERRIDE\\]",
            Pattern.CASE_INSENSITIVE),
        // Indirect / document injection
        Pattern.compile("when (you |the model )?(see|read|encounter|process) this",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(the following|these) (instructions? |commands? )?(override|supersede|replace)",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(act|behave|respond) (as if|like) (you are|you're) (now |a )?",
            Pattern.CASE_INSENSITIVE)
    );

    public PromptInjectionGuardRail() {
        super(Action.BLOCK);
        this.threshold = DEFAULT_THRESHOLD;
    }

    PromptInjectionGuardRail(double threshold) {
        super(Action.BLOCK);
        this.threshold = threshold;
    }

    @Override public String   name()     { return "prompt-injection"; }
    @Override public Position position() { return Position.PRE_LLM; }

    @Override
    public void handle(Request req, Response res, Next next) {
        // Check user input
        String input = extractText(req);
        if (input != null && isInjection(input)) {
            req.setAttribute(Attributes.GUARDRAIL_NAME, name());
            req.setAttribute(Attributes.GUARDRAIL_SCORE, 1.0);
            log.warn("Prompt injection detected in user input");
            res.status(400).json(Map.of(
                "error",     "Request blocked by guardrail",
                "guardrail", name(),
                "reason",    "Prompt injection detected in user input"));
            return;
        }

        // Check RAG-retrieved documents for indirect injection
        @SuppressWarnings("unchecked")
        List<Object> ragDocs = (List<Object>) req.attribute(Attributes.RAG_DOCUMENTS);
        if (ragDocs != null) {
            for (Object doc : ragDocs) {
                String content = doc.toString();
                if (isInjection(content)) {
                    req.setAttribute(Attributes.GUARDRAIL_NAME, name());
                    req.setAttribute(Attributes.GUARDRAIL_SCORE, 1.0);
                    log.warn("Prompt injection detected in RAG document");
                    res.status(400).json(Map.of(
                        "error",     "Request blocked by guardrail",
                        "guardrail", name(),
                        "reason",    "Prompt injection detected in retrieved document"));
                    return;
                }
            }
        }

        next.run();
    }

    private boolean isInjection(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(lower).find()) return true;
        }
        return false;
    }

    private static String extractText(Request req) {
        // Try parsed body first (available after CafeAI.json() runs)
        Object b = req.body("message");
        if (b != null) return b.toString();
        b = req.body("prompt");
        if (b != null) return b.toString();
        // Fall back to raw body — parse JSON field manually
        String t = req.bodyText();
        if (t != null && !t.isBlank()) {
            String trimmed = t.trim();
            if (trimmed.startsWith("{")) {
                String extracted = AbstractGuardRail.extractJsonField(trimmed, "message");
                if (extracted == null) extracted = AbstractGuardRail.extractJsonField(trimmed, "prompt");
                if (extracted != null) return extracted;
            }
            return t;
        }
        return null;
    }
}
