package io.cafeai.aiservices.adapter;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.cafeai.core.guardrails.GuardRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts a CafeAI {@link GuardRail} to LangChain4j's {@code InputGuardrail} /
 * {@code OutputGuardrail}, so guardrails registered on an agent are applied by
 * {@code AiServices} itself — no wrapper proxy.
 *
 * <p>Semantics match the engine's own path for {@code app.prompt()}: the guardrail's
 * {@link GuardRail.Action} decides the outcome ({@code BLOCK} fails the call, {@code WARN} and
 * {@code LOG} record it and let it proceed), and a failure names the guardrail but <em>not</em>
 * the reason. LangChain4j puts a failure's message into the exception it throws, and the reason —
 * a matched pattern, a moderation verdict — tells an attacker how the detector works. The reason
 * is logged instead.
 */
public final class GuardrailAdapters {

    private static final Logger log = LoggerFactory.getLogger(GuardrailAdapters.class);

    private GuardrailAdapters() {}

    /** Runs the guardrail against the user's message before the agent's LLM is called. */
    public static InputGuardrail asInput(GuardRail rail) {
        return new InputGuardrail() {
            @Override
            public InputGuardrailResult validate(UserMessage userMessage) {
                GuardRail.OutputCheckResult r = rail.checkInput(textOf(userMessage));
                if (r == null || !r.isViolation()) return success();
                switch (actionOf(rail)) {
                    case BLOCK -> {
                        log.warn("Agent input guardrail '{}' blocked the request: {}",
                                rail.name(), r.reason());
                        return failure("Guardrail '" + rail.name() + "' blocked the request");
                    }
                    case WARN -> log.warn("Agent input guardrail '{}' flagged the request (WARN): {}",
                            rail.name(), r.reason());
                    case LOG  -> log.info("Agent input guardrail '{}' flagged the request (LOG): {}",
                            rail.name(), r.reason());
                }
                return success();
            }
        };
    }

    /** Runs the guardrail against the agent's final response. */
    public static OutputGuardrail asOutput(GuardRail rail) {
        return new OutputGuardrail() {
            @Override
            public OutputGuardrailResult validate(AiMessage aiMessage) {
                String text = aiMessage.text();
                if (text == null || text.isBlank()) return success();
                GuardRail.OutputCheckResult r = rail.checkOutput(text);
                if (r == null || !r.isViolation()) return success();
                switch (actionOf(rail)) {
                    case BLOCK -> {
                        log.warn("Agent output guardrail '{}' blocked the response: {}",
                                rail.name(), r.reason());
                        return failure("Guardrail '" + rail.name() + "' blocked the response");
                    }
                    case WARN -> log.warn("Agent output guardrail '{}' flagged the response (WARN): {}",
                            rail.name(), r.reason());
                    case LOG  -> log.info("Agent output guardrail '{}' flagged the response (LOG): {}",
                            rail.name(), r.reason());
                }
                return success();
            }
        };
    }

    /** The user's text, whether the message is plain text or multimodal (text + image/pdf). */
    private static String textOf(UserMessage message) {
        if (message.hasSingleText()) return message.singleText();
        StringBuilder text = new StringBuilder();
        message.contents().forEach(c -> {
            if (c instanceof TextContent t) text.append(t.text()).append('\n');
        });
        return text.toString().stripTrailing();
    }

    /** A guardrail with no declared action blocks — the safe reading. */
    private static GuardRail.Action actionOf(GuardRail rail) {
        GuardRail.Action action = rail.action();
        return action != null ? action : GuardRail.Action.BLOCK;
    }
}
