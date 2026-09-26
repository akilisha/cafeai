package io.cafeai.examples;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.MonitoredAgent;
import io.cafeai.agentic.CafeAgentic;
import io.cafeai.agentic.CafeAgenticMonitor;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Jlama;

import java.util.Map;

/**
 * AgenticRoutingExample — reproduces the cross-agent context-loss problem demonstrated in
 * LangChain4j committer Mario Fusco's {@code langchain4j-agentic} talks: a follow-up question
 * handed from one specialist to another loses everything the first specialist was told, unless
 * the second specialist is explicitly told to pull a summary of the first's conversation.
 *
 * <p>Two specialists, each built via {@link CafeAgentic#agentBuilder} (so each carries the app's
 * default model, guardrails and observability automatically -- no repeated
 * {@code AgenticServices.agentBuilder(...).chatModel(...)} boilerplate per agent), composed with
 * {@code langchain4j-agentic}'s own {@code AgenticServices.sequenceBuilder(...)} -- CafeAI does
 * not wrap the composers, only single-agent construction.
 *
 * <p>{@code legalExpert}'s {@code @Agent(summarizedContext = "medicalExpert")} is the fix: it
 * pulls a summary of {@code medicalExpert}'s conversation into its own context, so a follow-up
 * question routed to the legal expert still knows what the medical expert was told. This is
 * built into {@code langchain4j-agentic} already -- CafeAI does not implement its own
 * context-summarizer.
 *
 * <p>The workflow interface extends {@code MonitoredAgent}; {@link CafeAgenticMonitor#route}
 * exposes its execution data as JSON -- the library itself has no HTML topology report (that
 * tooling is Quarkus Dev UI, not part of the core artifact), so this is CafeAI's honest
 * "HTTP identity" for the one thing every {@code langchain4j-agentic} user wants to inspect.
 *
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.AgenticRoutingExample
 *
 *   curl -H 'Content-Type: application/json' \
 *        -d '{"question":"I have had chest pain for two days, what should I do?"}' \
 *        http://localhost:8080/consult
 *
 *   curl http://localhost:8080/agentic/monitor
 * </pre>
 *
 * <p>Tool-free, single-turn agents -- coherence of the medical -&gt; legal handoff scales with
 * model size, same caveat as {@link AgentExample}. Qwen2.5-0.5B is enough to show the wiring, but
 * {@code summarizedContext} triggers a real extra LLM call under the hood (its own structured-output
 * request, see DEVELOPER_GUIDE §26.3) -- on a small model with no native ops on the classpath, this
 * step alone was observed taking several minutes on CPU, growing worse as its own context grows. For
 * anything beyond confirming the wiring, point {@code app.ai(...)} at a real provider or a
 * GPU-backed/native-accelerated local model instead.
 */
public class AgenticRoutingExample {

    /** Named {@code medicalExpert} so {@link LegalExpert} can name it in {@code summarizedContext}. */
    public interface MedicalExpert {
        @Agent(name = "medicalExpert", outputKey = "medicalAdvice",
               description = "Gives preliminary medical guidance for a symptom or condition.")
        String assess(String question);
    }

    /**
     * Pulls a summary of {@code medicalExpert}'s conversation -- the context-loss fix.
     *
     * <p>{@code question} here must be spelled exactly like {@link MedicalExpert#assess}'s own
     * parameter, not renamed to something like {@code followUpQuestion} -- a {@code sequenceBuilder}
     * workflow resolves each agent's parameters against the {@code AgenticScope}, which after the
     * entry call holds only the original input under its own parameter's name plus whatever prior
     * agents wrote under their {@code outputKey}. A parameter name matching neither throws
     * {@code MissingArgumentException} at invocation time, not at build time.
     */
    public interface LegalExpert {
        @Agent(name = "legalExpert", outputKey = "legalAdvice", summarizedContext = "medicalExpert",
               description = "Explains the liability/consent angle of a medical situation already discussed.")
        String advise(String question);
    }

    /** The composed workflow. Extending {@code MonitoredAgent} makes its execution data inspectable. */
    public interface Consultation extends MonitoredAgent {
        String consult(String question);
    }

    public static void main(String[] args) {
        var app = CafeAI.create();
        app.ai(Jlama.of("tjake/Qwen2.5-0.5B-Instruct-JQ4"));
        app.filter(CafeAI.json());

        MedicalExpert medicalExpert = CafeAgentic.agentBuilder(app, MedicalExpert.class).build();
        LegalExpert legalExpert     = CafeAgentic.agentBuilder(app, LegalExpert.class).build();

        Consultation consultation = AgenticServices.sequenceBuilder(Consultation.class)
            .subAgents(medicalExpert, legalExpert)
            .outputKey("legalAdvice")
            .build();

        app.post("/consult", (req, res, next) ->
            res.json(Map.of("answer", consultation.consult(req.body("question")))));

        app.get("/agentic/monitor", CafeAgenticMonitor.route(consultation.agentMonitor()));

        app.listen(8080, () ->
            System.out.println("☕ Agentic routing demo on http://localhost:8080/consult"));
    }
}
