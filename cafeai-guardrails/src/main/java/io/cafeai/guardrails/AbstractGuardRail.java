package io.cafeai.guardrails;

import io.cafeai.core.Attributes;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Base class for all guardrail implementations.
 *
 * <p>Handles the PRE_LLM / POST_LLM / BOTH positioning logic so each
 * concrete guardrail only needs to implement:
 * <ul>
 *   <li>{@link #checkInput(String)} -- inspect the user's prompt before LLM</li>
 *   <li>{@link #checkInputAsOutput(String)} -- inspect the LLM's response after it</li>
 * </ul>
 *
 * <p>Both return a {@link CheckResult} -- either {@code pass()} to continue
 * or {@code block(reason)} to reject the request with HTTP 400.
 *
 * <p>Violations are recorded in:
 * <ul>
 *   <li>{@code req.attribute(Attributes.GUARDRAIL_NAME)} -- the triggering guardrail</li>
 *   <li>{@code req.attribute(Attributes.GUARDRAIL_SCORE)} -- confidence (0.0-1.0)</li>
 * </ul>
 */
public abstract class AbstractGuardRail implements GuardRail {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final Action action;

    protected AbstractGuardRail(Action action) {
        this.action = action;
    }

    @Override
    public final Action action() { return action; }

    @Override
    public void handle(Request req, Response res, Next next) {
        // PRE_LLM check -- inspect the incoming prompt before the LLM is called.
        if (position() == Position.PRE_LLM || position() == Position.BOTH) {
            String input = extractInput(req);
            if (input != null && !input.isBlank()) {
                CheckResult result = checkInput(input);
                if (!result.passes()) {
                    onViolation(req, res, result);
                    return;
                }
            }
        }

        next.run();

        // POST_LLM check -- inspect the LLM's response after generation.
        // The response text is stored in Attributes.LLM_RESPONSE_TEXT by
        // the handler after calling app.prompt().call(). Guardrails with
        // POST_LLM or BOTH position check it here.
        if (position() == Position.POST_LLM || position() == Position.BOTH) {
            Object responseObj = req.attribute(Attributes.LLM_RESPONSE_TEXT);
            if (responseObj instanceof String responseText && !responseText.isBlank()) {
                CheckResult result = checkInputAsOutput(responseText);
                if (!result.passes()) {
                    log.warn("POST_LLM guardrail '{}' triggered on response: {}",
                        name(), result.reason());
                    // Replace the stored response with a safe refusal
                    req.setAttribute(Attributes.LLM_RESPONSE_TEXT,
                        "[Response blocked by guardrail: " + name() + "]");
                }
            }
        }
    }

    /**
     * Inspects text as output (POST_LLM). Delegates to {@link #checkInput(String)}
     * by default since most pattern-based guardrails apply the same logic to both
     * input and output. Override for output-specific behaviour.
     */
    protected CheckResult checkInputAsOutput(String output) {
        return checkInput(output);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Bridges the HTTP-middleware {@link CheckResult} type to the
     * direct-call {@link GuardRail.OutputCheckResult} type so
     * {@code CafeAIApp.applyPostLlmGuardrails()} can call this without
     * depending on {@code cafeai-guardrails}.
     */
    @Override
    public final GuardRail.OutputCheckResult checkOutput(String output) {
        CheckResult result = checkInputAsOutput(output);
        return result.passes()
            ? GuardRail.OutputCheckResult.pass()
            : GuardRail.OutputCheckResult.violation(result.reason());
    }

    /**
     * Inspects the user's input before it reaches the LLM.
     * Return {@link CheckResult#pass()} to allow, {@link CheckResult#block(String)} to reject.
     */
    protected CheckResult checkInput(String input) { return CheckResult.pass(); }

    // -- Private: request/response extraction ---------------------------------

    private static String extractInput(Request req) {
        // First try the parsed body (available after CafeAI.json() filter runs)
        Object bodyMsg = req.body("message");
        if (bodyMsg != null) return bodyMsg.toString();

        Object bodyPrompt = req.body("prompt");
        if (bodyPrompt != null) return bodyPrompt.toString();

        // Fall back to raw body text — works when guardrails run before json() filter
        String text = req.bodyText();
        if (text != null && !text.isBlank()) {
            // If it looks like JSON, try to extract "message" or "prompt" field
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                String extracted = extractJsonField(trimmed, "message");
                if (extracted == null) extracted = extractJsonField(trimmed, "prompt");
                if (extracted != null) return extracted;
            }
            return text;
        }
        return null;
    }

    /**
     * Minimal JSON field extractor — avoids a Jackson dependency in the guardrail layer.
     * Package-private so subclasses can reuse it for raw body parsing.
     */
    static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return null;
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart)))
            valueStart++;
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') return null;
        valueStart++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case '"'  -> sb.append('"');
                    case '\\'  -> sb.append('\\');
                    case 'n'  -> sb.append('\n');
                    case 't'  -> sb.append('\t');
                    default   -> sb.append(next);
                }
            } else if (c == '"') {
                return sb.toString(); // closing quote
            } else {
                sb.append(c);
            }
        }
        return null; // unterminated string
    }

    private void onViolation(Request req, Response res, CheckResult result) {
        req.setAttribute(Attributes.GUARDRAIL_NAME,  name());
        req.setAttribute(Attributes.GUARDRAIL_SCORE, result.score());
        log.warn("Guardrail '{}' triggered: {}", name(), result.reason());

        switch (action) {
            case BLOCK -> res.status(400).json(Map.of(
                "error",     "Request blocked by guardrail",
                "guardrail", name(),
                "reason",    result.reason()));
            case WARN -> log.warn("GUARDRAIL WARN [{}]: {}", name(), result.reason());
            case LOG  -> log.info("GUARDRAIL LOG [{}]: {}", name(), result.reason());
        }
    }

    // -- Nested: check result --------------------------------------------------

    /** The result of a guardrail check -- passes or blocks with a reason. */
    public record CheckResult(boolean passes, String reason, double score) {
        public static CheckResult pass()                     { return new CheckResult(true,  null,   0.0); }
        public static CheckResult block(String reason)       { return new CheckResult(false, reason, 1.0); }
        public static CheckResult block(String reason, double score) {
            return new CheckResult(false, reason, score);
        }
    }
}
