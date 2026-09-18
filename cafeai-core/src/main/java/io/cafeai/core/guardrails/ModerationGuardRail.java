package io.cafeai.core.guardrails;

import dev.langchain4j.model.moderation.Moderation;
import dev.langchain4j.model.moderation.ModerationModel;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * A guardrail backed by a LangChain4j {@link ModerationModel} — a model, not a pattern list, so
 * it catches what keyword rules cannot, and fits an application where the model itself is
 * non-deterministic and a fixed blocklist will always be one rephrasing behind.
 *
 * <p>CafeAI does not wrap the model: you pass LangChain4j's own type, so anything that
 * implements it works — OpenAI's ({@code OpenAI.moderation("omni-moderation-latest")}, or
 * {@code OpenAiModerationModel.builder()} for full control) or any other provider's.
 *
 * <pre>{@code
 *   app.guard(GuardRail.moderation(OpenAI.moderation("omni-moderation-latest")));
 *
 *   // moderate only what users send, and stay up if the moderation API is down:
 *   app.guard(GuardRail.moderation(model).at(GuardRail.Position.PRE_LLM).failOpen());
 * }</pre>
 *
 * <p>Applied like any other guardrail: by the engine to {@code app.prompt()}, {@code .vision()}
 * and {@code .audio()}, and to {@code app.agent(...)} through the LangChain4j guardrail adapters.
 * Its verdict is all LangChain4j's portable {@link Moderation} type carries — flagged or not; no
 * categories or scores — so that is all this reports. The reason stays in the logs.
 *
 * <p><strong>Fails closed.</strong> If the moderation call itself fails (network, quota, timeout),
 * the text is treated as a violation: a safety control that silently lets everything through when
 * its dependency is down only looks protective. Call {@link #failOpen()} where availability matters
 * more, and the failure is logged at {@code WARN}. Note that a fail-closed outage reaches an HTTP
 * client as the same {@code 400} as a real block. The moderation call is also on the request path
 * — it adds a round trip per checked text (twice per {@code prompt()} at the default position
 * {@link GuardRail.Position#BOTH}) — and providers cap input length, so a very long text can fail
 * the call.
 *
 * <p>The {@link #handle} middleware form does nothing: the engine enforces this guardrail on the
 * exact text the model sees, and moderating the HTTP body as well would double every call.
 *
 * <p>To reach LangChain4j's own hooks instead, an agent accepts the model directly:
 * {@code app.agent(...).configure(b -> b.moderationModel(model))} with {@code @Moderate} on the
 * agent method, which throws LangChain4j's {@code ModerationException}.
 */
public final class ModerationGuardRail implements GuardRail {

    private static final Logger log = LoggerFactory.getLogger(ModerationGuardRail.class);

    private final ModerationModel model;
    private final String name;
    private final Position position;
    private final Action action;
    private final boolean failOpen;

    private ModerationGuardRail(ModerationModel model, String name, Position position,
                                Action action, boolean failOpen) {
        this.model    = model;
        this.name     = name;
        this.position = position;
        this.action   = action;
        this.failOpen = failOpen;
    }

    /** Moderates input and output with {@code model}; blocks on a flag; fails closed. */
    public static ModerationGuardRail of(ModerationModel model) {
        Objects.requireNonNull(model, "ModerationModel must not be null");
        return new ModerationGuardRail(model, "moderation", Position.BOTH, Action.BLOCK, false);
    }

    /** Names this guardrail in logs and violation reports (default {@code "moderation"}). */
    public ModerationGuardRail named(String name) {
        return new ModerationGuardRail(model, Objects.requireNonNull(name), position, action, failOpen);
    }

    /** Where it applies: {@code PRE_LLM} (input), {@code POST_LLM} (output) or {@code BOTH} (default). */
    public ModerationGuardRail at(Position position) {
        return new ModerationGuardRail(model, name, Objects.requireNonNull(position), action, failOpen);
    }

    /** What a flag does: {@code BLOCK} (default), or {@code WARN} / {@code LOG} to record and continue. */
    public ModerationGuardRail action(Action action) {
        return new ModerationGuardRail(model, name, position, Objects.requireNonNull(action), failOpen);
    }

    /** If the moderation call itself fails, let the text through (logged at WARN) instead of blocking. */
    public ModerationGuardRail failOpen() {
        return new ModerationGuardRail(model, name, position, action, true);
    }

    @Override public String   name()     { return name; }
    @Override public Position position() { return position; }
    @Override public Action   action()   { return action; }

    @Override public OutputCheckResult checkInput(String input)   { return check(input); }
    @Override public OutputCheckResult checkOutput(String output) { return check(output); }

    /** Enforced by the engine, not the HTTP pipeline — see the class comment. */
    @Override
    public void handle(Request req, Response res, Next next) { next.run(); }

    private OutputCheckResult check(String text) {
        if (text == null || text.isBlank()) return OutputCheckResult.pass();   // nothing to moderate
        Moderation moderation;
        try {
            moderation = model.moderate(text).content();
        } catch (RuntimeException e) {
            if (failOpen) {
                log.warn("Moderation model failed; allowing the text (fail-open): {}", e.toString());
                return OutputCheckResult.pass();
            }
            log.error("Moderation model failed; blocking the text (fail-closed): {}", e.toString());
            return OutputCheckResult.violation(
                "moderation model unavailable (" + e.getClass().getSimpleName() + "); failing closed");
        }
        return moderation.flagged()
            ? OutputCheckResult.violation("content flagged by the moderation model")
            : OutputCheckResult.pass();
    }
}
