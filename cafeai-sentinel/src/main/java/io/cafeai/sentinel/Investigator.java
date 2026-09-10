package io.cafeai.sentinel;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.investigate.ClusterInvestigator;
import io.cafeai.sentinel.investigate.IncidentBrief;
import io.cafeai.sentinel.investigate.Investigation;

/**
 * Runs the agentic investigation for one incident. Given to
 * {@link IncidentTracker#investigator(Investigator)}; the tracker calls it off
 * the informer thread when an incident opens or grows a new reason, and folds
 * the result back in.
 *
 * <p>Supply one either from CafeAI —
 * {@code inc -> app.agent("cluster-investigator", ClusterInvestigator.class, null)
 * .investigate(IncidentBrief.of(inc))} — or, for a standalone (non-CafeAI)
 * deployment, from {@link #using(ChatModel, Object...)}.
 */
@FunctionalInterface
public interface Investigator {

    /**
     * Investigate {@code incident} against the live cluster and return a
     * structured result. May block; may throw — the tracker isolates both.
     */
    Investigation investigate(Incident incident);

    /**
     * A {@link ClusterInvestigator} built directly on a LangChain4j
     * {@link ChatModel} and the given {@code @Tool} objects (a {@code KubeTools}),
     * using {@link ClusterInvestigator#SYSTEM_PROMPT}. For callers not running a
     * CafeAI app.
     */
    static Investigator using(ChatModel model, Object... tools) {
        ClusterInvestigator agent = AiServices.builder(ClusterInvestigator.class)
                .chatModel(model)
                .tools(tools)
                .build();
        return incident -> agent.investigate(IncidentBrief.of(incident));
    }
}
