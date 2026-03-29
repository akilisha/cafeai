package io.cafeai.security;

/**
 * Listener for {@link SecurityEvent}s raised by the AI security layer.
 *
 * <p>Register via {@link AiSecurity#onEvent(SecurityEventListener)}.
 * Use it to forward events to a SIEM, audit database, alerting system,
 * or custom log sink.
 *
 * <pre>{@code
 *   AiSecurity.onEvent(event -> switch (event) {
 *       case SecurityEvent.InjectionAttempt e ->
 *           auditLog.record("INJECTION", e.requestPath(), e.source());
 *       case SecurityEvent.DataLeakageAttempt e ->
 *           alerting.critical("DATA_LEAK", e.principal(), e.documentSourceId());
 *       case SecurityEvent.CachePoisoningAttempt e ->
 *           auditLog.record("CACHE_POISON", e.requestPath());
 *   });
 * }</pre>
 */
@FunctionalInterface
public interface SecurityEventListener {
    void onEvent(SecurityEvent event);
}
