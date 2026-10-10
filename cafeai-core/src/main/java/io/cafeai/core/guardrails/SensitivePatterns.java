package io.cafeai.core.guardrails;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The shapes of sensitive text CafeAI recognises: personal data ({@link #PII}) and credentials
 * ({@link #SECRETS}). One list, used both by the PII and secrets guardrails, which refuse text
 * that contains them, and by audit text capture, which redacts them before text is kept.
 *
 * <p>Each match is named by its kind ({@code [EMAIL]}, {@code [AWS_ACCESS_KEY_ID]}), never by its
 * value. Found by shape, not by meaning: a value altered so it no longer looks like one (spaced
 * out, encoded, split) isn't found.
 */
public final class SensitivePatterns {

    private SensitivePatterns() {}

    /** A kind of sensitive text, and the shape that finds it. */
    public record Kind(String label, Pattern pattern) { }

    /** Personal data: email addresses, phone numbers, US SSNs, card numbers, IPv4 addresses. */
    public static final List<Kind> PII = List.of(
        new Kind("EMAIL",
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")),
        // Card numbers before phone numbers: a phone number's shape fits inside a card number's.
        new Kind("CREDIT_CARD",
            Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|" +
                "3[47][0-9]{13}|6(?:011|5[0-9]{2})[0-9]{12})\\b")),
        new Kind("SSN",
            Pattern.compile("\\b\\d{3}[\\s\\-]\\d{2}[\\s\\-]\\d{4}\\b")),
        new Kind("PHONE",
            Pattern.compile("(\\+?\\d[\\s.\\-]?)?\\(?\\d{3}\\)?[\\s.\\-]?\\d{3}[\\s.\\-]?\\d{4}")),
        new Kind("IPV4",
            Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
                "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b"))
    );

    /**
     * Credentials: AWS, GitHub, Slack, Stripe, Google, Hugging Face, NVIDIA, OpenAI and Anthropic
     * keys, PEM private keys, JSON Web Tokens, credentials in a URL, and a {@code password=…} or
     * {@code api_key: …} assignment with a long value.
     */
    public static final List<Kind> SECRETS = List.of(
        new Kind("AWS_ACCESS_KEY_ID",   Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
        new Kind("GITHUB_TOKEN",        Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})")),
        new Kind("SLACK_TOKEN",         Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}")),
        new Kind("STRIPE_KEY",          Pattern.compile("\\b[sr]k_(?:live|test)_[0-9A-Za-z]{16,}")),
        new Kind("GOOGLE_API_KEY",      Pattern.compile("\\bAIza[0-9A-Za-z_\\-]{35}")),
        new Kind("HUGGINGFACE_TOKEN",   Pattern.compile("\\bhf_[A-Za-z0-9]{30,}")),
        new Kind("NVIDIA_API_KEY",      Pattern.compile("\\bnvapi-[A-Za-z0-9_\\-]{20,}")),
        new Kind("LLM_API_KEY",         Pattern.compile("\\bsk-(?:ant-|proj-)?[A-Za-z0-9_\\-]{20,}")),
        new Kind("PRIVATE_KEY",         Pattern.compile("-----BEGIN (?:[A-Z]+ )?PRIVATE KEY(?: BLOCK)?-----")),
        new Kind("JWT",                 Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{8,}\\.eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}")),
        new Kind("URL_CREDENTIALS",     Pattern.compile("\\b[a-z][a-z0-9+.\\-]*://[^\\s/:@]+:[^\\s/@]+@[^\\s]+")),
        new Kind("CREDENTIAL_ASSIGNMENT", Pattern.compile(
            "\\b(?:api[_-]?key|secret|passwd|password|access[_-]?token|auth[_-]?token)\\b\\s*[:=]\\s*[\"']?[A-Za-z0-9/+_\\-]{16,}",
            Pattern.CASE_INSENSITIVE))
    );

    /** {@code text} with every match of {@code kinds} replaced by {@code [LABEL]}. */
    public static String scrub(String text, List<Kind> kinds) {
        if (text == null) return null;
        for (Kind k : kinds) {
            text = k.pattern().matcher(text).replaceAll("[" + k.label() + "]");
        }
        return text;
    }
}
