package io.cafeai.agentic;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.agent.AgentBuilder;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.model.chat.ChatModel;
import io.cafeai.agentic.internal.AgenticGuardrailAdapters;
import io.cafeai.agentic.internal.AgenticObserveListener;
import io.cafeai.agentic.internal.AgenticSupportHolder;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.spi.AgentBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-wires an app's registered model, guardrails and observability into a real
 * {@link AgentBuilder}, the same job {@code cafeai-aiservices}' {@code AgentRegistry} does for
 * plain {@code AiServices}. Only single-agent construction is pre-wired: compose the returned,
 * already-built agents with {@link AgenticServices}' own {@code sequenceBuilder}/
 * {@code parallelBuilder}/{@code loopBuilder}/{@code conditionalBuilder}/{@code supervisorBuilder}
 * directly -- their model/guardrail parity with {@code AgentBuilder} was never confirmed, so
 * this deliberately does not guess at wrapping them.
 *
 * <p>Requires the app to have been built via {@code CafeAI.create()} (so its
 * {@code AgenticBridge} SPI has run) and to have a default AI provider registered via
 * {@code app.ai(...)}, or the agent interface's own {@code @ChatModelSupplier}/explicit
 * {@code .chatModel(...)} call must supply one instead.
 *
 * <p><strong>Process-global, like {@code AgenticServices} itself.</strong> {@code app} is
 * accepted (and must be the app that was actually built) so a mismatched or not-yet-built app
 * fails fast here rather than producing a builder wired to the wrong -- or no -- capabilities;
 * the wiring it reads is the most recently built {@code CafeAI} app in this JVM, the same
 * single-app-per-process assumption {@code cafeai-flight}'s use of {@code GlobalOpenTelemetry}
 * already makes. Building more than one app in the same JVM (e.g. parallel tests) is not
 * supported by this call.
 */
public final class CafeAgentic {

    private CafeAgentic() {}

    /**
     * Returns a real {@code AgenticServices.agentBuilder(type)} instance, pre-wired with the
     * app's default model, registered guardrails, and observability. Add {@code .outputKey(...)},
     * {@code .tools(...)}, or anything else the workflow needs, then call {@code .build()}.
     */
    public static <T> AgentBuilder<T, ?> agentBuilder(CafeAI app, Class<T> type) {
        AgentBridge.AgentSupport support = requireSupport(app);
        ChatModel model = defaultChatModel(support);

        AgentBuilder<T, ?> builder = AgenticServices.agentBuilder(type).chatModel(model);

        List<InputGuardrail> inputRails = new ArrayList<>();
        List<OutputGuardrail> outputRails = new ArrayList<>();
        for (GuardRail rail : support.guardRails()) {
            GuardRail.Position pos = rail.position();
            if (pos == GuardRail.Position.PRE_LLM || pos == GuardRail.Position.BOTH) {
                inputRails.add(AgenticGuardrailAdapters.asInput(rail));
            }
            if (pos == GuardRail.Position.POST_LLM || pos == GuardRail.Position.BOTH) {
                outputRails.add(AgenticGuardrailAdapters.asOutput(rail));
            }
        }
        if (!inputRails.isEmpty())  builder.inputGuardrails(inputRails.toArray(InputGuardrail[]::new));
        if (!outputRails.isEmpty()) builder.outputGuardrails(outputRails.toArray(OutputGuardrail[]::new));

        builder.listener(new AgenticObserveListener(support.observeBridge()));

        return builder;
    }

    /**
     * The app's default {@link ChatModel} -- the same one {@link #agentBuilder} resolves
     * internally, exposed because {@code langchain4j-agentic}'s composer builders
     * ({@code sequenceBuilder}, {@code supervisorBuilder}, ...) each require their own
     * {@code .chatModel(...)} call and CafeAI does not wrap those builders (see class Javadoc),
     * so there is otherwise no way to hand them the app's registered model without reaching
     * into {@code cafeai-aiservices} internals. This is a plain accessor, not a builder --
     * it adds no composition behavior of its own.
     */
    public static ChatModel chatModel(CafeAI app) {
        return defaultChatModel(requireSupport(app));
    }

    private static AgentBridge.AgentSupport requireSupport(CafeAI app) {
        if (app == null) {
            throw new IllegalArgumentException("app must not be null");
        }
        AgentBridge.AgentSupport support = AgenticSupportHolder.support();
        if (support == null) {
            throw new IllegalStateException(
                "cafeai-agentic is not initialised — build the app via CafeAI.create().");
        }
        return support;
    }

    private static ChatModel defaultChatModel(AgentBridge.AgentSupport support) {
        AiProvider provider = support.defaultProvider();
        if (provider == null) {
            throw new IllegalStateException(
                "No default model registered. Call app.ai(...) before building an agentic agent, "
                + "or set the model explicitly on the returned builder.");
        }
        return support.chatModel(provider);
    }
}
