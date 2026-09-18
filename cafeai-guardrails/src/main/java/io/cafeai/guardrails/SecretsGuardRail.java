package io.cafeai.guardrails;

import io.cafeai.core.guardrails.TextNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Credentials and secrets guardrail — API keys, tokens, private keys, connection strings.
 *
 * <p>Checked on both sides. <strong>In</strong>: a user pastes a stack trace or a config file into
 * the chat and the key in it is sent to a third-party model provider, and into your logs and
 * conversation memory. <strong>Out</strong>: the model repeats a credential it was given in the
 * system prompt, a retrieved document or an earlier turn.
 *
 * <p>Detected by the shape of the credential, not by guessing:
 * <ul>
 *   <li>AWS access key ids, GitHub, Slack, Stripe, Google, Hugging Face, NVIDIA, OpenAI and
 *       Anthropic keys</li>
 *   <li>PEM private keys, JSON Web Tokens</li>
 *   <li>credentials embedded in a URL ({@code postgres://user:password@host/db})</li>
 *   <li>a generic {@code password=…} / {@code api_key: …} assignment with a long value</li>
 * </ul>
 *
 * <p>A report names the <em>kind</em> ({@code AWS_ACCESS_KEY_ID}), never the value: a guardrail that
 * logged the secret it found would be a leak of its own. Text is
 * {@linkplain TextNormalizer#canonical canonicalised} first so full-width forms and zero-width
 * characters do not hide a key; case is preserved, since it is part of a key's shape. A key that has
 * been altered so it no longer looks like one — spaced out, base64-encoded, split across messages —
 * is not found.
 *
 * <pre>{@code
 *   app.guard(GuardRail.secrets());
 * }</pre>
 *
 * <p>{@link #scrub(String)} redacts secrets from text you are about to log or forward.
 */
public final class SecretsGuardRail extends AbstractGuardRail {

    private record Kind(String label, Pattern pattern) {}

    private static final List<Kind> KINDS = List.of(
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

    public SecretsGuardRail() {
        super(Action.BLOCK);
    }

    SecretsGuardRail(Action action) {
        super(action);
    }

    /** A copy that {@code BLOCK}s, or only {@code WARN}s / {@code LOG}s, on detection. */
    public SecretsGuardRail action(Action action) {
        return new SecretsGuardRail(action);
    }

    @Override public String   name()     { return "secrets"; }
    @Override public Position position() { return Position.BOTH; }

    @Override
    protected CheckResult screenInput(String input) {
        List<String> found = detect(input);
        return found.isEmpty() ? CheckResult.pass()
            : CheckResult.block("Secret detected in input: " + String.join(", ", found));
    }

    @Override
    protected CheckResult checkInputAsOutput(String output) {
        List<String> found = detect(output);
        return found.isEmpty() ? CheckResult.pass()
            : CheckResult.block("Secret detected in output: " + String.join(", ", found));
    }

    /** Replaces every detected secret with {@code [KIND]}, e.g. {@code [AWS_ACCESS_KEY_ID]}. */
    public static String scrub(String text) {
        if (text == null) return null;
        String out = TextNormalizer.canonical(text);
        for (Kind k : KINDS) {
            out = k.pattern().matcher(out).replaceAll("[" + k.label() + "]");
        }
        return out;
    }

    private static List<String> detect(String text) {
        String canonical = TextNormalizer.canonical(text);
        List<String> found = new ArrayList<>();
        for (Kind k : KINDS) {
            if (k.pattern().matcher(canonical).find()) found.add(k.label());
        }
        return found;
    }
}
