package io.cafeai.agentic.internal;

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
 * Adapts a CafeAI {@link GuardRail} to LangChain4j's {@code InputGuardrail}/{@code OutputGuardrail},
 * so guardrails registered on an agentic agent are applied by {@code AgentBuilder} itself -- no
 * wrapper proxy. Same job, same semantics, as {@code cafeai-aiservices}'s {@code GuardrailAdapters}
 * -- kept as a separate small copy here rather than a shared extraction, since moving already-shipped,
 * tested code as a side effect of adding this module is a larger blast radius than this needs.
 */
public final class AgenticGuardrailAdapters {

    private static final Logger log = LoggerFactory.getLogger(AgenticGuardrailAdapters.class);

    private AgenticGuardrailAdapters() {}

    /** Runs the guardrail against the user's message before the agent's LLM is called. */
    public static InputGuardrail asInput(GuardRail rail) {
        return new InputGuardrail() {
            @Override
            public InputGuardrailResult validate(UserMessage userMessage) {
                GuardRail.OutputCheckResult r = rail.checkInput(textOf(userMessage));
                if (r == null || !r.isViolation()) return success();
                switch (actionOf(rail)) {
                    case BLOCK -> {
                        log.warn("Agentic input guardrail '{}' blocked the request: {}",
                                rail.name(), r.reason());
                        return failure("Guardrail '" + rail.name() + "' blocked the request");
                    }
                    case WARN -> log.warn("Agentic input guardrail '{}' flagged the request (WARN): {}",
                            rail.name(), r.reason());
                    case LOG  -> log.info("Agentic input guardrail '{}' flagged the request (LOG): {}",
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
                        log.warn("Agentic output guardrail '{}' blocked the response: {}",
                                rail.name(), r.reason());
                        return failure("Guardrail '" + rail.name() + "' blocked the response");
                    }
                    case WARN -> log.warn("Agentic output guardrail '{}' flagged the response (WARN): {}",
                            rail.name(), r.reason());
                    case LOG  -> log.info("Agentic output guardrail '{}' flagged the response (LOG): {}",
                            rail.name(), r.reason());
                }
                return success();
            }
        };
    }

    private static String textOf(UserMessage message) {
        if (message.hasSingleText()) return message.singleText();
        StringBuilder text = new StringBuilder();
        message.contents().forEach(c -> {
            if (c instanceof TextContent t) text.append(t.text()).append('\n');
        });
        return text.toString().stripTrailing();
    }

    private static GuardRail.Action actionOf(GuardRail rail) {
        GuardRail.Action action = rail.action();
        return action != null ? action : GuardRail.Action.BLOCK;
    }
}
