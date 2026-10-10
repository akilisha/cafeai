package io.cafeai.core.audit;

import io.cafeai.core.guardrails.SensitivePatterns;
import io.cafeai.core.guardrails.TextNormalizer;

/**
 * Removes what must not be kept from text before it is captured for audit
 * ({@link TextCapture}).
 */
@FunctionalInterface
public interface Redactor {

    /** {@code text}, with what must not be kept taken out. */
    String redact(String text);

    /**
     * Credentials, then personal data ({@link SensitivePatterns}), each replaced by its kind:
     * {@code "mail me at ann@example.com"} becomes {@code "mail me at [EMAIL]"}. Text is
     * {@linkplain TextNormalizer#canonical canonicalised} first, so full-width characters and
     * zero-width spaces don't hide a match.
     */
    static Redactor standard() {
        return text -> text == null ? null : SensitivePatterns.scrub(
                SensitivePatterns.scrub(TextNormalizer.canonical(text), SensitivePatterns.SECRETS), SensitivePatterns.PII);
    }

    /** {@code this}, then {@code next}: for an organisation's own terms (project names, ids) on top of the standard ones. */
    default Redactor andThen(Redactor next) {
        return text -> next.redact(redact(text));
    }
}
