package io.cafeai.sentinel.incident;

/** Lifecycle state of an {@link Incident}. */
public enum IncidentStatus {

    /** Actively accumulating signals. */
    OPEN,

    /** No affected pods remain and the cooldown has elapsed. Terminal for Phase 2. */
    RESOLVED
}
