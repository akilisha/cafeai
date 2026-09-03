package io.cafeai.agents.adapter;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.cafeai.core.guardrails.GuardRail;

/**
 * Adapts a CafeAI {@link GuardRail} to LangChain4j's {@code InputGuardrail} /
 * {@code OutputGuardrail}, so guardrails registered on an agent are applied by
 * {@code AiServices} itself — no wrapper proxy.
 */
public final class GuardrailAdapters {

    private GuardrailAdapters() {}

    /** Runs the guardrail against the user's message before the agent's LLM is called. */
    public static InputGuardrail asInput(GuardRail rail) {
        return new InputGuardrail() {
            @Override
            public InputGuardrailResult validate(UserMessage userMessage) {
                GuardRail.OutputCheckResult r = rail.checkInput(userMessage.singleText());
                return r.isViolation()
                    ? failure("Guardrail '" + rail.name() + "': " + r.reason())
                    : success();
            }
        };
    }

    /** Runs the guardrail against the agent's final response. */
    public static OutputGuardrail asOutput(GuardRail rail) {
        return new OutputGuardrail() {
            @Override
            public OutputGuardrailResult validate(AiMessage aiMessage) {
                GuardRail.OutputCheckResult r = rail.checkOutput(aiMessage.text());
                return r.isViolation()
                    ? failure("Guardrail '" + rail.name() + "': " + r.reason())
                    : success();
            }
        };
    }
}
