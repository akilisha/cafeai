package io.cafeai.connect;

/**
 * Removes what must not reach a log line or the {@code /health} response from a connection URL:
 * the {@code user:password@} part and the query string, where JDBC URLs often carry
 * {@code ?user=...&password=...}.
 */
final class Urls {

    private Urls() {}

    static String redact(String url) {
        if (url == null) return null;
        String out = url.replaceFirst("(?<=//)[^/@?#]*@", "");
        int cut = indexOfAny(out, '?', ';');
        return cut >= 0 ? out.substring(0, cut) : out;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        return i < 0 ? j : (j < 0 ? i : Math.min(i, j));
    }
}
