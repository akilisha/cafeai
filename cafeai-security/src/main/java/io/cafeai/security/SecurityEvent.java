package io.cafeai.security;

import java.time.Instant;
import java.util.UUID;

/**
 * A security event raised when the AI security layer detects a threat.
 *
 * <p>Every event carries a unique ID, timestamp, event type, the triggering
 * input, and the request ID for correlation with access logs and OTel traces.
 *
 * <p>Events are emitted to SLF4J at WARN level by default. Wire a
 * {@link SecurityEventListener} via {@link AiSecurity#onEvent(SecurityEventListener)}
 * to forward them to a SIEM, audit database, or alerting system.
 */
public sealed interface SecurityEvent
        permits SecurityEvent.InjectionAttempt,
                SecurityEvent.DataLeakageAttempt,
                SecurityEvent.CachePoisoningAttempt {

    /** Unique event ID -- use for deduplication in audit systems. */
    String eventId();

    /** When the event was detected. */
    Instant timestamp();

    /** The request path that triggered the event. */
    String requestPath();

    /** The input text that triggered detection. */
    String triggeringInput();

    // -- Concrete event types --------------------------------------------------

    /**
     * Raised when a prompt injection attempt is detected in user input or
     * RAG-retrieved content. More strictly enforced than the guardrail version --
     * all injection signals are blocked regardless of confidence threshold.
     */
    record InjectionAttempt(
            String eventId,
            Instant timestamp,
            String requestPath,
            String triggeringInput,
            String source          // "user_input" | "rag_document"
    ) implements SecurityEvent {}

    /**
     * Raised when a user attempts to access a RAG document they are not
     * authorised to see. Requires an {@code AUTH_PRINCIPAL} attribute on the request.
     */
    record DataLeakageAttempt(
            String eventId,
            Instant timestamp,
            String requestPath,
            String triggeringInput,
            String documentSourceId,
            String principal
    ) implements SecurityEvent {}

    /**
     * Raised when an adversarial prompt is detected that appears designed to
     * corrupt cached responses for future users.
     */
    record CachePoisoningAttempt(
            String eventId,
            Instant timestamp,
            String requestPath,
            String triggeringInput
    ) implements SecurityEvent {}

    // -- Factory helpers -------------------------------------------------------

    static InjectionAttempt injection(String path, String input, String source) {
        return new InjectionAttempt(UUID.randomUUID().toString(),
            Instant.now(), path, input, source);
    }

    static DataLeakageAttempt dataLeakage(String path, String input,
                                           String docId, String principal) {
        return new DataLeakageAttempt(UUID.randomUUID().toString(),
            Instant.now(), path, input, docId, principal);
    }

    static CachePoisoningAttempt cachePoisoning(String path, String input) {
        return new CachePoisoningAttempt(UUID.randomUUID().toString(),
            Instant.now(), path, input);
    }
}
