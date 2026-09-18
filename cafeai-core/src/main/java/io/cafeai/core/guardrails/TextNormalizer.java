package io.cafeai.core.guardrails;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * Canonicalises text before a detector looks at it, so a pattern written for {@code ignore previous
 * instructions} also sees the spellings an attacker uses to get around it.
 *
 * <p>A keyword or regex detector compares characters; a language model reads meaning. Everything
 * between the two is an evasion: {@code ignore} written in full-width letters, with a zero-width
 * space in the middle, with a Cyrillic {@code o} for the Latin one, with an accent on the {@code i},
 * or in capitals. The model reads all of them as the same word. {@link #normalize} folds them to one
 * form, and the pattern-based guardrails in {@code cafeai-guardrails} match against it.
 *
 * <p>The result is for <em>matching only</em>: it is lower-cased, accent-stripped and
 * homoglyph-folded, so never show it to a user or forward it to a model. It does not defeat
 * evasions that change the words themselves — translation, paraphrase, base64, splitting a phrase
 * across several messages — and is no substitute for a moderation model
 * ({@link GuardRail#moderation}).
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    /**
     * Folds text to a canonical form for matching: compatibility-decomposed (full-width, ligatures,
     * circled and superscript forms become plain), stripped of invisible and control characters and
     * combining marks, homoglyph-folded (common Cyrillic and Greek look-alikes become Latin),
     * lower-cased, with whitespace runs collapsed to a single space. {@code null} becomes {@code ""}.
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(s.length());
        boolean pendingSpace = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isInvisible(cp) || Character.getType(cp) == Character.NON_SPACING_MARK) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace) { out.append(' '); pendingSpace = false; }
            int lower = Character.toLowerCase(cp);
            Character latin = HOMOGLYPHS.get(lower);
            out.appendCodePoint(latin != null ? latin : lower);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Only the invisible characters removed and compatibility forms made plain — case and spelling
     * untouched. For detectors where case matters, such as the shape of an API key.
     */
    public static String canonical(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!isInvisible(cp)) out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** Zero-width, bidi-control, soft-hyphen, BOM and tag characters, and non-whitespace controls. */
    private static boolean isInvisible(int cp) {
        int type = Character.getType(cp);
        if (type == Character.FORMAT) return true;
        return Character.isISOControl(cp) && !Character.isWhitespace(cp);
    }

    /** Look-alikes an attacker swaps in for Latin letters. Keys are lower-case code points. */
    private static final Map<Integer, Character> HOMOGLYPHS = Map.ofEntries(
        // Cyrillic
        Map.entry(0x0430, 'a'), Map.entry(0x0435, 'e'), Map.entry(0x043E, 'o'), Map.entry(0x0440, 'p'),
        Map.entry(0x0441, 'c'), Map.entry(0x0443, 'y'), Map.entry(0x0445, 'x'), Map.entry(0x0456, 'i'),
        Map.entry(0x0458, 'j'), Map.entry(0x0455, 's'), Map.entry(0x04BB, 'h'), Map.entry(0x051B, 'q'),
        Map.entry(0x051D, 'w'), Map.entry(0x0501, 'd'), Map.entry(0x043A, 'k'), Map.entry(0x043C, 'm'),
        Map.entry(0x0442, 't'), Map.entry(0x043D, 'h'), Map.entry(0x0432, 'b'),
        // Greek
        Map.entry(0x03B1, 'a'), Map.entry(0x03BF, 'o'), Map.entry(0x03BD, 'v'), Map.entry(0x03C1, 'p'),
        Map.entry(0x03B9, 'i'), Map.entry(0x03BA, 'k'), Map.entry(0x03C4, 't'), Map.entry(0x03C5, 'u'),
        Map.entry(0x03B5, 'e'), Map.entry(0x03C7, 'x')
    );
}
