package io.cafeai.loop;

import io.cafeai.core.guardrails.SensitivePatterns;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * What leaves the machine, screened with reversible placeholders (ROADMAP-03, 2.4;
 * {@code DESIGN_SPEC.md}, The agents): each secret or piece of personal data CafeAI's guardrails
 * recognise becomes a stable placeholder on its way to the model ({@code [EMAIL_1_c4e1]}), and is
 * restored exactly when the agent writes a file, commits, or answers the developer. The model never
 * sees the value, and code written back is never corrupted by placeholders.
 *
 * <p>The patterns are matched on the text as it is (not a normalised copy), so everything that
 * isn't screened comes back byte for byte. Each placeholder carries a tag of the session's, so text
 * that already contains something like {@code [EMAIL_1]} is never mistaken for one.
 */
public class Screen {

    private static final List<SensitivePatterns.Kind> KINDS = new ArrayList<>();

    static {
        KINDS.addAll(SensitivePatterns.SECRETS);   // secrets first: a key inside a URL is a key, not an address
        KINDS.addAll(SensitivePatterns.PII);
    }

    private final String tag;
    private final Map<String, String> placeholderOf = new HashMap<>();
    private final Map<String, String> valueOf = new HashMap<>();
    private final Map<String, Integer> counts = new HashMap<>();

    public Screen() {
        this(randomTag());
    }

    /** A screen whose placeholders carry {@code tag}: the evals' recordings replay only if every run's are the same. */
    Screen(String tag) {
        this.tag = tag;
    }

    private static String randomTag() {
        byte[] random = new byte[2];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    /** A screen that changes nothing: for the developer's own tools. */
    public static Screen none() {
        return new Screen() {
            @Override public String out(String text) { return text; }
            @Override public String in(String text) { return text; }
        };
    }

    /** The text as it may leave the machine: every secret and piece of personal data replaced by its placeholder. */
    public synchronized String out(String text) {
        if (text == null) return null;
        String screened = text;
        for (SensitivePatterns.Kind kind : KINDS) {
            Matcher m = kind.pattern().matcher(screened);
            StringBuilder replaced = new StringBuilder();
            int from = 0;
            while (m.find()) {
                replaced.append(screened, from, m.start()).append(placeholder(kind.label(), m.group()));
                from = m.end();
            }
            if (from > 0) screened = replaced.append(screened.substring(from)).toString();
        }
        return screened;
    }

    /** The text as the developer, a file or a commit should have it: every placeholder this screen made restored. */
    public synchronized String in(String text) {
        if (text == null || valueOf.isEmpty()) return text;
        String restored = text;
        for (Map.Entry<String, String> e : valueOf.entrySet()) restored = restored.replace(e.getKey(), e.getValue());
        return restored;
    }

    private String placeholder(String label, String value) {
        return placeholderOf.computeIfAbsent(value, v -> {
            int n = counts.merge(label, 1, Integer::sum);
            String placeholder = "[" + label + "_" + n + "_" + tag + "]";
            valueOf.put(placeholder, v);
            return placeholder;
        });
    }
}
