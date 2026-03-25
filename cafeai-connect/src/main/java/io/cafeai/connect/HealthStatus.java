package io.cafeai.connect;

import java.time.Instant;

/**
 * The reachability state of an out-of-process service.
 *
 * <p>Unlike in-process modules which are binary (present or absent),
 * out-of-process services have three states:
 *
 * <ul>
 *   <li>{@link #REACHABLE} — the service responded within the probe timeout</li>
 *   <li>{@link #UNREACHABLE} — the service did not respond or refused the connection</li>
 *   <li>{@link #DEGRADED} — the service responded but reported an internal problem</li>
 * </ul>
 *
 * <p>This distinction matters. {@code UNREACHABLE} means "we can't get to it" —
 * try a fallback. {@code DEGRADED} means "we reached it but it's struggling" —
 * the fallback may not help; the right response might be to reduce load or alert.
 */
public final class HealthStatus {

    public enum State { REACHABLE, UNREACHABLE, DEGRADED }

    private final State   state;
    private final String  service;
    private final String  detail;
    private final long    latencyMs;
    private final Instant checkedAt;

    private HealthStatus(State state, String service,
                         String detail, long latencyMs) {
        this.state     = state;
        this.service   = service;
        this.detail    = detail;
        this.latencyMs = latencyMs;
        this.checkedAt = Instant.now();
    }

    public static HealthStatus reachable(String service, long latencyMs) {
        return new HealthStatus(State.REACHABLE, service, null, latencyMs);
    }

    public static HealthStatus unreachable(String service, String reason) {
        return new HealthStatus(State.UNREACHABLE, service, reason, -1);
    }

    public static HealthStatus degraded(String service, String reason) {
        return new HealthStatus(State.DEGRADED, service, reason, -1);
    }

    public State   state()      { return state; }
    public String  service()    { return service; }
    public String  detail()     { return detail; }
    public long    latencyMs()  { return latencyMs; }
    public Instant checkedAt()  { return checkedAt; }
    public boolean isHealthy()  { return state == State.REACHABLE; }

    @Override
    public String toString() {
        return state == State.REACHABLE
            ? service + ": reachable (" + latencyMs + "ms)"
            : service + ": " + state.name().toLowerCase()
                + (detail != null ? " — " + detail : "");
    }
}
