package io.cafeai.core.guardrails;

/**
 * Thrown when a {@link GuardRail} with action {@link GuardRail.Action#BLOCK} rejects the
 * text about to be sent to the model, so no model call is made.
 *
 * <p>It is a {@link RuntimeException}, so it propagates out of {@code app.prompt(...).call()},
 * {@code .stream(...)}, {@code app.vision(...)} and {@code app.audio(...)}. Inside an HTTP route
 * with no error handler that claims it, CafeAI answers {@code 400} with only the guardrail's
 * name — <strong>not</strong> the reason, which can reveal how the detector works and so help
 * an attacker route around it. The reason stays on this exception and in the logs:
 *
 * <pre>{@code
 *   app.onError((err, req, res, next) -> {
 *       if (err instanceof GuardRailViolationException v) {
 *           audit.record(v.guardrail(), v.reason());
 *           res.status(400).json(Map.of("error", "We can't help with that request."));
 *           return;
 *       }
 *       next.run();
 *   });
 * }</pre>
 *
 * <p>Only input can be blocked this way. A {@code POST_LLM} violation replaces the response
 * with a refusal instead, because by then the model has already been called (and paid for).
 */
public class GuardRailViolationException extends RuntimeException {

    private final String guardrail;
    private final GuardRail.Position position;
    private final String reason;

    public GuardRailViolationException(String guardrail, GuardRail.Position position, String reason) {
        super(position + " guardrail '" + guardrail + "' blocked the request: " + reason);
        this.guardrail = guardrail;
        this.position  = position;
        this.reason    = reason;
    }

    /** The name of the guardrail that rejected the request. */
    public String guardrail() { return guardrail; }

    /** Where in the pipeline it fired ({@code PRE_LLM} for a blocked request). */
    public GuardRail.Position position() { return position; }

    /** Why it fired. Internal detail — do not echo it to the client. */
    public String reason() { return reason; }
}
