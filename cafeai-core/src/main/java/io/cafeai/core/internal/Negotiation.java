package io.cafeai.core.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP content negotiation for {@code Accept}, {@code Accept-Charset},
 * {@code Accept-Encoding} and {@code Accept-Language} (RFC 9110 §12).
 *
 * <p>Picks, from the values the server can offer, the one the client prefers:
 * highest quality ({@code q=}) first, then the most specific matching range, then the
 * order the server listed. A quality of 0 means "not acceptable". An absent header means
 * the client accepts anything, so the first offer wins.
 *
 * <p>Package-private: reached through {@code req.accepts*()} and {@code res.format()}.
 */
final class Negotiation {

    private Negotiation() {}

    /** Express-style short names accepted by {@code req.accepts("json", "html")}. */
    private static final Map<String, String> SHORT_TYPES = Map.of(
        "json", "application/json",
        "html", "text/html",
        "text", "text/plain",
        "txt",  "text/plain",
        "xml",  "application/xml",
        "css",  "text/css",
        "js",   "text/javascript");

    private record Range(String value, double q) {}

    /** Best media type among {@code offered} for an {@code Accept} header, or {@code null}. */
    static String media(String acceptHeader, String... offered) {
        return best(acceptHeader, offered, Negotiation::mediaSpecificity, Negotiation::normaliseMedia);
    }

    /** Best charset for an {@code Accept-Charset} header, or {@code null}. */
    static String charset(String header, String... offered) {
        return best(header, offered, Negotiation::tokenSpecificity, o -> o);
    }

    /** Best content coding for an {@code Accept-Encoding} header, or {@code null}. */
    static String encoding(String header, String... offered) {
        return best(header, offered, Negotiation::tokenSpecificity, o -> o);
    }

    /** Best language for an {@code Accept-Language} header, or {@code null}. */
    static String language(String header, String... offered) {
        return best(header, offered, Negotiation::languageSpecificity, o -> o);
    }

    // ── core ──────────────────────────────────────────────────────────────────

    /** How well a range matches an offer: 0 = no match, higher = more specific. */
    private interface Specificity { int of(String range, String offer); }

    private static String best(String header, String[] offered, Specificity spec,
                               java.util.function.UnaryOperator<String> normalise) {
        if (offered == null || offered.length == 0) return null;
        if (header == null || header.isBlank()) return offered[0];   // accepts anything

        List<Range> ranges = parse(header);
        String winner = null;
        double bestQ = 0;
        int bestSpec = 0;
        for (String offer : offered) {
            String candidate = normalise.apply(offer);
            double q = 0;
            int s = 0;
            for (Range r : ranges) {
                int m = spec.of(r.value(), candidate);
                // most specific matching range decides the quality for this offer
                if (m > s || (m == s && m > 0 && r.q() > q)) { s = m; q = r.q(); }
            }
            if (s == 0 || q <= 0) continue;          // no matching range, or q=0 (refused)
            if (q > bestQ || (q == bestQ && s > bestSpec)) {
                winner = offer; bestQ = q; bestSpec = s;
            }
        }
        return winner;
    }

    private static List<Range> parse(String header) {
        List<Range> out = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] pieces = part.split(";");
            String value = pieces[0].trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            double q = 1.0;
            for (int i = 1; i < pieces.length; i++) {
                String p = pieces[i].trim();
                if (p.regionMatches(true, 0, "q=", 0, 2)) {
                    try {
                        q = Math.max(0, Math.min(1, Double.parseDouble(p.substring(2).trim())));
                    } catch (NumberFormatException ignored) { /* keep 1.0 */ }
                }
            }
            out.add(new Range(value, q));
        }
        return out;
    }

    // ── matching ──────────────────────────────────────────────────────────────

    private static String normaliseMedia(String offer) {
        String lower = offer.toLowerCase(Locale.ROOT);
        return lower.contains("/") ? lower : SHORT_TYPES.getOrDefault(lower, lower);
    }

    /** exact type/subtype = 3, type/* = 2, *&#47;* = 1, otherwise 0. */
    private static int mediaSpecificity(String range, String offer) {
        if (range.equals(offer)) return 3;
        if (range.equals("*/*") || range.equals("*")) return 1;
        if (range.endsWith("/*") && offer.startsWith(range.substring(0, range.length() - 1))) return 2;
        return 0;
    }

    /** exact = 3, {@code *} = 1, otherwise 0. */
    private static int tokenSpecificity(String range, String offer) {
        if (range.equalsIgnoreCase(offer)) return 3;
        return range.equals("*") ? 1 : 0;
    }

    /** exact = 3, range is a prefix of the offer ("en" for "en-US") = 2, {@code *} = 1. */
    private static int languageSpecificity(String range, String offer) {
        String o = offer.toLowerCase(Locale.ROOT);
        if (range.equals(o)) return 3;
        if (o.startsWith(range + "-")) return 2;
        return range.equals("*") ? 1 : 0;
    }
}
