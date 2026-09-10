package io.cafeai.sentinel.triage;

/**
 * The outcome of triaging one correlated pod snapshot, in ascending severity.
 *
 * <p>Only {@link #NOTABLE} and {@link #ERROR} open or update an incident;
 * {@link #BENIGN} is the steady state and, when seen for a pod that was
 * contributing to an incident, counts towards that pod's recovery.
 */
public enum Verdict {

    /** Normal lifecycle — image pulled, container created/started, pod scheduled,
     *  healthy and running, clean completion. */
    BENIGN,

    /** A real state change worth surfacing but not a failure — node drain,
     *  preemption, taint eviction. Published, not investigated. */
    NOTABLE,

    /** A failure — crash loop, image pull failure, OOM kill, failed schedule /
     *  mount, a probe failing repeatedly, a Failed pod. */
    ERROR;

    /** True when this verdict is at least as severe as {@code other}. */
    public boolean atLeast(Verdict other) {
        return compareTo(other) >= 0;
    }
}
