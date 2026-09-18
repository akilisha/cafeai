package io.cafeai.security;

import java.time.Instant;
import java.util.UUID;

/**
 * A security event raised when the security layer blocks a request.
 *
 * <p>Every event carries a unique ID, a timestamp, the request path, and the triggering input.
 * Wire a {@link SecurityEventListener} via {@link AiSecurity#onEvent(SecurityEventListener)} to
 * forward them to a SIEM, audit database, or alerting system.
 *
 * <p>Sealed, so an exhaustive {@code switch} over it stays exhaustive as event types are added
 * deliberately; today there is one.
 */
public sealed interface SecurityEvent permits SecurityEvent.InjectionAttempt {

    /** Unique event ID -- use for deduplication in audit systems. */
    String eventId();

    /** When the event was detected. */
    Instant timestamp();

    /** The request path that triggered the event. */
    String requestPath();

    /** The input text that triggered detection (truncated). */
    String triggeringInput();

    // -- Concrete event types --------------------------------------------------

    /** Raised when a prompt injection attempt is detected in a user's message and blocked. */
    record InjectionAttempt(
            String eventId,
            Instant timestamp,
            String requestPath,
            String triggeringInput
    ) implements SecurityEvent {}

    // -- Factory helpers -------------------------------------------------------

    static InjectionAttempt injection(String path, String input) {
        return new InjectionAttempt(UUID.randomUUID().toString(), Instant.now(), path, input);
    }
}
