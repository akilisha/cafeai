package io.cafeai.sentinel.investigate;

import java.util.List;

/**
 * The structured outcome of an agentic investigation into an incident — what
 * {@link ClusterInvestigator#investigate} returns and what
 * {@link io.cafeai.sentinel.incident.Incident#investigation()} carries.
 *
 * <p>The prose fields ({@code summary}, {@code likelyCause}) are
 * non-deterministic and are never asserted verbatim in tests; the structured
 * fields ({@code category}, {@code confidence}, {@code relatedObjects}) are.
 *
 * @param summary          one or two sentences: what is wrong, in an operator's terms
 * @param category         the class of root cause
 * @param likelyCause      the specific cause, concretely — which limit, which image,
 *                         which missing object
 * @param confidence       how firmly the evidence supports {@code likelyCause}
 * @param suggestedActions concrete, non-destructive steps an operator can take now
 *                         (e.g. "raise resources.limits.memory above 128Mi"),
 *                         most useful first
 * @param relatedObjects   {@code Kind/name} references the investigation touched
 *                         (e.g. {@code Deployment/web}, {@code ConfigMap/web-config},
 *                         {@code Node/minikube})
 */
public record Investigation(
        String summary,
        CauseCategory category,
        String likelyCause,
        Confidence confidence,
        List<String> suggestedActions,
        List<String> relatedObjects) {

    public Investigation {
        suggestedActions = suggestedActions == null ? List.of() : List.copyOf(suggestedActions);
        relatedObjects = relatedObjects == null ? List.of() : List.copyOf(relatedObjects);
    }
}
