package io.cafeai.core.audit;

import java.time.Duration;
import java.util.Objects;

/**
 * Opting in to keep what was asked and answered ({@code app.auditText(...)}), for settings that
 * must, such as regulated ones. Off unless an app asks for it: the text is personal data, and can
 * hold anything a person typed.
 *
 * <pre>{@code
 *   app.auditText(TextCapture.to(TranscriptSink.jsonLines(Path.of("/var/audit/text")))
 *       .keepFor(Duration.ofDays(90)));
 * }</pre>
 *
 * <ul>
 *   <li><b>Separate.</b> {@link Transcript}s go to their own sink, never to the
 *       {@link AuditSink}s of {@code app.audit(...)}, which stay metadata only; so the text can be
 *       kept elsewhere, for less time, by fewer people.</li>
 *   <li><b>Redacted</b> before it leaves CafeAI: credentials and personal data by default
 *       ({@link Redactor#standard()}), or as {@link #redactWith(Redactor)} says.</li>
 *   <li><b>Kept for a time the app decides</b> ({@link #keepFor(Duration)}, required: there is
 *       no default to forget), carried on each record as {@link Transcript#keepUntil()}.</li>
 * </ul>
 * Retrieved documents and system prompts are not captured: only the caller's last message and
 * the model's answer.
 */
public final class TextCapture {

    private final TranscriptSink sink;
    private Duration keepFor;
    private Redactor redactor = Redactor.standard();

    private TextCapture(TranscriptSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Captures to {@code sink}. Say how long with {@link #keepFor(Duration)}. */
    public static TextCapture to(TranscriptSink sink) {
        return new TextCapture(sink);
    }

    /** How long captured text may be kept. Required. */
    public TextCapture keepFor(Duration keepFor) {
        Objects.requireNonNull(keepFor, "keepFor");
        if (keepFor.isNegative() || keepFor.isZero()) throw new IllegalArgumentException("keepFor must be positive");
        this.keepFor = keepFor;
        return this;
    }

    /** What to take out of the text before it is kept (default {@link Redactor#standard()}). */
    public TextCapture redactWith(Redactor redactor) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        return this;
    }

    public TranscriptSink sink()   { return sink; }
    public Duration keepFor()      { return keepFor; }
    public Redactor redactor()     { return redactor; }

    /** @throws IllegalStateException if no retention was given */
    public TextCapture validated() {
        if (keepFor == null) {
            throw new IllegalStateException("Say how long captured text may be kept: TextCapture.to(sink).keepFor(...)");
        }
        return this;
    }
}
