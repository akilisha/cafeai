package io.cafeai.core.audit;

/**
 * Receives {@link AuditEvent}s. Register with {@code app.audit(sink)}:
 *
 * <pre>{@code
 *   app.audit(event -> auditLog.append(json(event)));
 * }</pre>
 *
 * <p>Called synchronously, on the thread that made the call or ran the guardrail, so keep it
 * quick: hand slow work (a database, a network hop) to a queue. A sink that throws is logged
 * and skipped; it never fails the request.
 */
@FunctionalInterface
public interface AuditSink {

    /** Receives one event. */
    void record(AuditEvent event);
}
