package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.guardrails.TextNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII detection guardrail.
 *
 * <p>Detects personally identifiable information in both the user's prompt (pre-LLM) and the
 * model's response (post-LLM). Text is {@linkplain TextNormalizer#canonical canonicalised} first,
 * so full-width digits and zero-width characters do not hide a number.
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
 * <p>Detection blocks: a request or response containing PII is refused. A guardrail cannot
 * rewrite text in flight, so there is no "redact and continue" mode; to redact text you are about
 * to log or forward, call {@link #scrub(String)} yourself.
 *
 * <pre>{@code
 *   app.guard(GuardRail.pii());
 * }</pre>
 */
public final class PiiGuardRail extends AbstractGuardRail {

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

    @Override public String   name()     { return "pii"; }
    @Override public Position position() { return Position.BOTH; }

    @Override
    protected CheckResult screenInput(String input) {
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
        String canonical = TextNormalizer.canonical(text);
        for (PiiPattern pp : PATTERNS) {
            Matcher m = pp.pattern().matcher(canonical);
            if (m.find()) found.add(pp.label());
        }
        return found;
    }

    private record PiiPattern(String label, Pattern pattern) {}
}
