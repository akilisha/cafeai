package io.cafeai.sentinel.investigate;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * The agentic investigation step — a LangChain4j {@code AiService} interface.
 *
 * <p>Given a brief describing a coalesced incident (see
 * {@link IncidentBrief}), the agent drives the read-only {@link KubeTools}
 * against the live cluster and returns a structured {@link Investigation}.
 *
 * <p>Wire it either through CafeAI —
 * {@code app.agent("cluster-investigator", ClusterInvestigator.class).tool(kubeTools).model(...)} —
 * or standalone via {@link io.cafeai.sentinel.Investigator#using}. The
 * {@link SystemMessage} here is the default; a CafeAI {@code .system(...)} on the
 * agent config overrides it.
 */
public interface ClusterInvestigator {

    /** The default system prompt — also available to callers that build the agent themselves. */
    String SYSTEM_PROMPT = """
            You are a Kubernetes / OpenShift incident investigator.

            You are given a brief describing a failing workload in ONE namespace.
            Use the read-only tools to inspect the live cluster and determine the
            single most likely root cause. Investigate before you conclude — do
            not answer from the brief alone.

            How to investigate:
            - For a CrashLoopBackOff, read the PREVIOUS container's logs
              (previous=true) — that is where the stack trace or fatal error is.
            - Correlate signals: a low memory limit + an OOMKilled last-termination
              + a restart loop is one story; a recent image change + ImagePullBackOff
              is another; a CreateContainerConfigError + a missing ConfigMap named in
              the events is another.
            - Check the owning Deployment and its ReplicaSet history when a rollout
              may be involved. Check node conditions for pressure/NotReady. Check
              namespace quota and LimitRange when scheduling or OOM is in play.

            Your answer:
            - summary: one or two plain sentences an on-call engineer can act on.
            - likelyCause: the specific cause — name the limit, the image, the object.
            - suggestedActions: concrete steps to take now (e.g. "raise
              spec.template.spec.containers[0].resources.limits.memory above 128Mi",
              not "look into memory"). Never recommend destructive actions
              (deleting data, force-deleting namespaces, disabling security).
            - confidence: LOW if the evidence is thin or you are guessing.
            - relatedObjects: Kind/name of everything you inspected that matters.
            """;

    @SystemMessage(SYSTEM_PROMPT)
    Investigation investigate(@UserMessage String incidentBrief);
}
