package io.cafeai.sentinel.incident;

/**
 * Emitted by {@link io.cafeai.sentinel.IncidentTracker} on every incident
 * lifecycle transition. In Phase 5 this is what an {@code IncidentSink} receives.
 *
 * @param type     what just happened
 * @param incident the incident snapshot after the transition
 */
public record IncidentEvent(Type type, Incident incident) {

    public enum Type {
        /** A new incident was opened for a workload. */
        OPENED,
        /** An open incident accumulated another signal. */
        UPDATED,
        /** An open incident's pods all recovered and the cooldown elapsed. */
        RESOLVED
    }
}
