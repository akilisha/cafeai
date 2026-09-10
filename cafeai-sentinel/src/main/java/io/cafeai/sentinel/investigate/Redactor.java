package io.cafeai.sentinel.investigate;

import io.cafeai.guardrails.PiiGuardRail;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Scrubs credentials and PII out of cluster text — container logs, pod YAML
 * (env values), event messages — <em>before</em> it reaches the LLM prompt, the
 * {@link io.cafeai.sentinel.incident.Incident}, or a log line. ROADMAP-18
 * Phase 4: a secret in a pod's logs must never leave the process in the clear.
 *
 * <p>Two layers: sentinel-specific secret shapes (bearer / basic auth headers,
 * URL credentials, {@code password=} / {@code token=} style assignments, AWS keys,
 * JWTs / ServiceAccount tokens, PEM private-key blocks), then the
 * {@code cafeai-guardrails} PII scrubber for emails, phone numbers, SSNs, credit
 * cards and IPv4 addresses (an IP in an access log is PII under GDPR — internal
 * pod/node IPs get caught too, an accepted trade for the compliance guarantee).
 */
public final class Redactor {

    private static final Redactor ENABLED = new Redactor(true);
    private static final Redactor DISABLED = new Redactor(false);

    private record Rule(Pattern pattern, String replacement) { }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("(?i)\\bauthorization\\s*[:=]\\s*bearer\\s+\\S+"),
                    "Authorization: Bearer [REDACTED]"),
            new Rule(Pattern.compile("(?i)\\bauthorization\\s*[:=]\\s*basic\\s+\\S+"),
                    "Authorization: Basic [REDACTED]"),
            // scheme://user:pass@host  ->  scheme://[REDACTED]@host
            new Rule(Pattern.compile("([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^\\s:/@]+:[^\\s:/@]+@"),
                    "$1[REDACTED]@"),
            // key=value / key: value where the key contains a secret-ish word
            // (matches DB_PASSWORD, api_key, client-secret, ...); value may be quoted
            new Rule(Pattern.compile(
                    "(?i)([\\w.\\-]*(?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key|"
                            + "client[_-]?secret|auth[_-]?token|session[_-]?token|private[_-]?key)[\\w.\\-]*)"
                            + "\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|\\S+)"),
                    "$1=[REDACTED]"),
            new Rule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "[REDACTED_AWS_KEY]"),
            // JWT / Kubernetes ServiceAccount token
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+\\b"),
                    "[REDACTED_TOKEN]"),
            new Rule(Pattern.compile(
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]+?-----END [A-Z ]*PRIVATE KEY-----"),
                    "[REDACTED_PRIVATE_KEY]"));

    private final boolean on;

    private Redactor(boolean on) {
        this.on = on;
    }

    public static Redactor enabled() {
        return ENABLED;
    }

    public static Redactor disabled() {
        return DISABLED;
    }

    public static Redactor of(boolean enabled) {
        return enabled ? ENABLED : DISABLED;
    }

    /** Returns {@code text} with credentials and PII replaced by labelled placeholders. */
    public String redact(String text) {
        if (!on || text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        return PiiGuardRail.scrub(out);
    }

    /** Applies {@link #redact(String)} to every free-text field of an investigation result. */
    public Investigation redact(Investigation investigation) {
        if (!on || investigation == null) {
            return investigation;
        }
        return new Investigation(
                redact(investigation.summary()),
                investigation.category(),
                redact(investigation.likelyCause()),
                investigation.confidence(),
                investigation.suggestedActions().stream().map(this::redact).toList(),
                investigation.relatedObjects().stream().map(this::redact).toList());
    }
}
