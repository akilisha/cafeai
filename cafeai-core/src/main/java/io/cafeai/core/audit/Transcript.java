package io.cafeai.core.audit;

import io.cafeai.core.identity.Identity;

import java.time.Instant;
import java.util.Objects;

/**
 * What was asked and what was answered, in one model call: captured only when the app opts in
 * ({@code app.auditText(...)}), redacted before it gets here, and kept no later than
 * {@link #keepUntil()}.
 *
 * <p>Separate from {@link AuditEvent} on purpose: audit records hold metadata only, and a sink
 * registered for them never receives text.
 *
 * @param caller    who it was for, or {@code null} (anonymous, or no request behind it)
 * @param route     the request's route, or {@code UsageReport.NO_REQUEST}
 * @param model     the model id
 * @param prompt    the caller's last message, redacted; {@code null} when there was none
 * @param answer    the model's answer, redacted; {@code null} when it answered with tool calls only
 * @param keepUntil when this record must be gone: the app's retention for captured text
 */
public record Transcript(Instant at, Identity.Key caller, String route, String model,
                         String prompt, String answer, Instant keepUntil) {
    public Transcript {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(keepUntil, "keepUntil");
    }
}
