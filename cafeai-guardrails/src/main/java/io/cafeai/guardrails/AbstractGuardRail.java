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
 *   <li>{@link #checkOutput(String)} -- inspect the LLM's response after it</li>
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
        // PRE_LLM check -- inspect the incoming prompt
        if (position() == Position.PRE_LLM || position() == Position.BOTH) {
            String input = extractInput(req);
            if (input != null && !input.isBlank()) {
                CheckResult result = checkInput(input);
                if (!result.passes()) {
                    onViolation(req, res, result);
                    return; // do not proceed
                }
            }
        }

        // Call next -- the LLM runs here (if this is the final pre-LLM guard)
        next.run();

        // POST_LLM check -- inspect the response
        if (position() == Position.POST_LLM || position() == Position.BOTH) {
            String output = extractOutput(req);
            if (output != null && !output.isBlank()) {
                CheckResult result = checkOutput(output);
                if (!result.passes()) {
                    onViolation(req, res, result);
                }
            }
        }
    }

    /**
     * Inspects the user's input before it reaches the LLM.
     * Return {@link CheckResult#pass()} to allow, {@link CheckResult#block(String)} to reject.
     */
    protected CheckResult checkInput(String input) { return CheckResult.pass(); }

    /**
     * Inspects the LLM's output after generation.
     * Return {@link CheckResult#pass()} to allow, {@link CheckResult#block(String)} to reject.
     */
    protected CheckResult checkOutput(String output) { return CheckResult.pass(); }

    // -- Private: request/response extraction ---------------------------------

    private static String extractInput(Request req) {
        // Try bodyText first, then body("message"), then body("prompt")
        String text = req.bodyText();
        if (text != null && !text.isBlank()) return text;
        Object body = req.body("message");
        if (body != null) return body.toString();
        body = req.body("prompt");
        if (body != null) return body.toString();
        return null;
    }

    private static String extractOutput(Request req) {
        Object response = req.attribute(Attributes.LAST_RESPONSE_TEXT);
        return response != null ? response.toString() : null;
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
