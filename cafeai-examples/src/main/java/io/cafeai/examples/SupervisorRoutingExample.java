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
 * SupervisorRoutingExample — the other half of the pool-of-agents pattern
 * {@link AgenticRoutingExample} started: instead of a fixed medical-then-legal pipeline, a
 * supervisor picks whichever specialist actually fits the question, out of a pool of three, one
 * request at a time.
 *
 * <p>The three specialists are each built via {@link CafeAgentic#agentBuilder} exactly as before
 * -- pre-wired with the app's model, guardrails and observability. The supervisor itself is a
 * composer, like {@code sequenceBuilder}, so CafeAI does not pre-wire it (see
 * {@link CafeAgentic}'s Javadoc); it needs its own {@code .chatModel(...)}, which
 * {@link CafeAgentic#chatModel} hands it -- the same model {@code agentBuilder} would have used,
 * just exposed directly because the composer builders each require it set explicitly.
 *
 * <pre>{@code
 * Consultation supervisor = AgenticServices.supervisorBuilder(Consultation.class)
 *     .chatModel(CafeAgentic.chatModel(app))
 *     .subAgents(medicalExpert, legalExpert, technicalExpert)
 *     .build();
 * }</pre>
 *
 * <p>Unlike {@code sequenceBuilder}, which agent (or agents) fire is the supervisor's own
 * LLM-driven decision at request time, not something the caller lays out -- the same class of
 * "supervisor delegating across a pool of specialists" pattern demonstrated live in Mario Fusco's
 * {@code langchain4j-agentic} talks: this example validates the same shape works cleanly bound
 * to CafeAI's HTTP layer, it does not reproduce his source.
 *
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.SupervisorRoutingExample
 *
 *   curl -H 'Content-Type: application/json' \
 *        -d '{"question":"Is it legal for a clinic to withhold my test results until I pay?"}' \
 *        http://localhost:8080/ask
 *
 *   curl http://localhost:8080/agentic/monitor
 * </pre>
 *
 * <p>A supervisor's routing decision is itself an extra LLM call before any specialist runs, on
 * top of whichever specialist(s) it then calls -- slower than the fixed-sequence example for the
 * same reason a router adds a hop, and on a small, CPU-only, non-native-accelerated local model
 * (see {@link AgenticRoutingExample}'s caveat on {@code summarizedContext}) this compounds: a
 * supervisor call demands more of the model than a single agent call does. Observed directly with
 * Qwen2.5-0.5B: the routing <em>decision</em> itself was reliably correct (it picked
 * {@code legalExpert} for a liability question every time this was tried) -- but the model then
 * failed to construct that agent's call <em>arguments</em> correctly, echoing back the agent's own
 * name/description instead of a real {@code question} value. Choosing well and filling in a
 * structured argument map are two different demands on the model; a model that clears the first
 * does not automatically clear the second. Point {@code app.ai(...)} at a real provider or a
 * larger/accelerated model for a demo that completes end to end.
 */
public class SupervisorRoutingExample {

    public interface MedicalExpert {
        @Agent(name = "medicalExpert",
               description = "Handles questions about symptoms, medical conditions, and general health guidance.")
        String assess(String question);
    }

    public interface LegalExpert {
        @Agent(name = "legalExpert",
               description = "Handles questions about liability, consent, contracts, and legal implications.")
        String advise(String question);
    }

    public interface TechnicalExpert {
        @Agent(name = "technicalExpert",
               description = "Handles questions about how a device, system, or piece of software works or fails.")
        String explain(String question);
    }

    /**
     * The supervisor. Extends {@code MonitoredAgent} so its routing + specialist calls are
     * inspectable.
     *
     * <p>The parameter <strong>must</strong> be named {@code request} -- unlike
     * {@code sequenceBuilder}/{@code agentBuilder}, which bind to whichever parameter name you
     * choose, {@code supervisorBuilder}'s planner falls back to reading the fixed scope key
     * {@code "request"} by default when no {@code @SupervisorRequest}-annotated method or
     * explicit {@code .requestGenerator(...)} is supplied. Name it anything else and the
     * supervisor sees an empty request -- no error, just a routing decision made from nothing.
     */
    public interface Consultation extends MonitoredAgent {
        String handle(String request);
    }

    public static void main(String[] args) {
        var app = CafeAI.create();
        app.ai(Jlama.of("tjake/Qwen2.5-0.5B-Instruct-JQ4"));
        app.filter(CafeAI.json());

        MedicalExpert medicalExpert     = CafeAgentic.agentBuilder(app, MedicalExpert.class).build();
        LegalExpert legalExpert         = CafeAgentic.agentBuilder(app, LegalExpert.class).build();
        TechnicalExpert technicalExpert = CafeAgentic.agentBuilder(app, TechnicalExpert.class).build();

        Consultation supervisor = AgenticServices.supervisorBuilder(Consultation.class)
            .chatModel(CafeAgentic.chatModel(app))
            .subAgents(medicalExpert, legalExpert, technicalExpert)
            .build();

        app.post("/ask", (req, res, next) ->
            res.json(Map.of("answer", supervisor.handle(req.body("question")))));

        app.get("/agentic/monitor", CafeAgenticMonitor.route(supervisor.agentMonitor()));

        app.listen(8080, () ->
            System.out.println("☕ Supervisor routing demo on http://localhost:8080/ask"));
    }
}
