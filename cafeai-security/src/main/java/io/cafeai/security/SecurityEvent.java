package io.cafeai.security;

import io.cafeai.core.identity.Identity;

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

    /**
     * Who sent it: the issuer and subject of the request's verified identity, or {@code null}
     * when the request was anonymous.
     */
    Identity.Key caller();

    // -- Concrete event types --------------------------------------------------

    /** Raised when a prompt injection attempt is detected in a user's message and blocked. */
    record InjectionAttempt(
            String eventId,
            Instant timestamp,
            String requestPath,
            String triggeringInput,
            Identity.Key caller
    ) implements SecurityEvent {

        /** An attempt with no known caller. */
        public InjectionAttempt(String eventId, Instant timestamp, String requestPath, String triggeringInput) {
            this(eventId, timestamp, requestPath, triggeringInput, null);
        }
    }

    // -- Factory helpers -------------------------------------------------------

    static InjectionAttempt injection(String path, String input) {
        return injection(path, input, null);
    }

    static InjectionAttempt injection(String path, String input, Identity.Key caller) {
        return new InjectionAttempt(UUID.randomUUID().toString(), Instant.now(), path, input, caller);
    }
}
