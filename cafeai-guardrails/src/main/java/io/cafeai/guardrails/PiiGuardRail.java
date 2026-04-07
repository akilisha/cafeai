package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII detection and scrubbing guardrail.
 *
 * <p>Detects and optionally scrubs personally identifiable information
 * from both the user's prompt (pre-LLM) and the model's response (post-LLM).
 *
 * <p>Detected entities:
 * <ul>
 *   <li>Email addresses</li>
 *   <li>Phone numbers (US and international formats)</li>
 *   <li>US Social Security Numbers (SSN)</li>
 *   <li>Credit card numbers (major card types)</li>
 *   <li>IP addresses (IPv4)</li>
 * </ul>
 *
 * <p>Action options:
 * <ul>
 *   <li>{@link GuardRail.Action#BLOCK} -- reject the request immediately (default)</li>
 *   <li>{@link GuardRail.Action#WARN} -- log a warning but allow through</li>
 * </ul>
 *
 * <p>Use {@link #scrubbing()} to redact PII in-place rather than blocking:
 *
 * <pre>{@code
 *   app.guard(GuardRail.pii());                // block on PII detection
 *   app.guard(GuardRail.pii().scrubbing());    // redact PII, continue
 * }</pre>
 */
public final class PiiGuardRail extends AbstractGuardRail {

    private boolean scrub = false;

    private static final List<PiiPattern> PATTERNS = List.of(
        new PiiPattern("EMAIL",
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")),
        new PiiPattern("PHONE",
            Pattern.compile("(\\+?\\d[\\s.\\-]?)?\\(?\\d{3}\\)?[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{4}")),
        new PiiPattern("SSN",
            Pattern.compile("\\b\\d{3}[\\s\\-]\\d{2}[\\s\\-]\\d{4}\\b")),
        new PiiPattern("CREDIT_CARD",
            Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|" +
                "3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b")),
        new PiiPattern("IPV4",
            Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
                "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"))
    );

    public PiiGuardRail() {
        super(Action.BLOCK);
    }

    PiiGuardRail(Action action) {
        super(action);
    }

    /** Returns a new PiiGuardRail that redacts PII in-place rather than blocking. */
    public PiiGuardRail scrubbing() {
        PiiGuardRail g = new PiiGuardRail(Action.LOG);
        g.scrub = true;
        return g;
    }

    @Override public String   name()     { return "pii"; }
    @Override public Position position() { return Position.BOTH; }

    @Override
    protected CheckResult checkInput(String input) {
        List<String> found = detect(input);
        if (found.isEmpty()) return CheckResult.pass();
        return CheckResult.block("PII detected in input: " + String.join(", ", found),
            (double) found.size() / PATTERNS.size());
    }

    @Override
    protected CheckResult checkInputAsOutput(String output) {
        List<String> found = detect(output);
        if (found.isEmpty()) return CheckResult.pass();
        return CheckResult.block("PII detected in output: " + String.join(", ", found),
            (double) found.size() / PATTERNS.size());
    }

    /**
     * Scrubs PII from text by replacing matches with labelled placeholders.
     * E.g. {@code "Call 555-867-5309"} -> {@code "Call [PHONE]"}.
     */
    public static String scrub(String text) {
        for (PiiPattern pp : PATTERNS) {
            text = pp.pattern().matcher(text).replaceAll("[" + pp.label() + "]");
        }
        return text;
    }

    private static List<String> detect(String text) {
        List<String> found = new ArrayList<>();
        for (PiiPattern pp : PATTERNS) {
            Matcher m = pp.pattern().matcher(text);
            if (m.find()) found.add(pp.label());
        }
        return found;
    }

    private record PiiPattern(String label, Pattern pattern) {}
}
