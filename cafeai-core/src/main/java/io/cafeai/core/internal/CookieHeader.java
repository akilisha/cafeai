package io.cafeai.core.internal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses a request {@code Cookie} header (RFC 6265 §5.4) into name/value pairs.
 *
 * <p>Values are percent-decoded when they are validly encoded and otherwise left as sent
 * ({@code +} is not a space here, unlike in form encoding). Surrounding double quotes are
 * removed. If a name appears more than once, the first occurrence wins.
 */
final class CookieHeader {

    private CookieHeader() {}

    static Map<String, String> parse(String header) {
        if (header == null || header.isBlank()) return Map.of();
        Map<String, String> cookies = new LinkedHashMap<>();
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;                       // no name, or no '='
            String name  = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            cookies.putIfAbsent(name, decode(value));
        }
        return Collections.unmodifiableMap(cookies);
    }

    private static String decode(String value) {
        if (value.indexOf('%') < 0) return value;
        try {
            // URLDecoder treats '+' as a space; in a cookie it is a literal plus.
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return value;
        }
    }
}
