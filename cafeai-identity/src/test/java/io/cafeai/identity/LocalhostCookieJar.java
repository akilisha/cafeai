package io.cafeai.identity;

import java.net.CookieHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A cookie jar that behaves like a browser on {@code localhost}: it sends cookies marked
 * {@code Secure} over plain http, as browsers do for localhost, which they treat as a secure
 * context. Java's own {@code CookieManager} doesn't, so a Keycloak login over http on localhost
 * would lose its session cookies. Cookies are kept per host and sent to any port, like a browser.
 */
final class LocalhostCookieJar extends CookieHandler {

    private final Map<String, Map<String, String>> byHost = new ConcurrentHashMap<>();

    @Override
    public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
        Map<String, String> jar = byHost.get(uri.getHost());
        if (jar == null || jar.isEmpty()) return Map.of();
        StringBuilder header = new StringBuilder();
        jar.forEach((name, value) -> header.append(header.isEmpty() ? "" : "; ").append(name).append('=').append(value));
        return Map.of("Cookie", List.of(header.toString()));
    }

    @Override
    public void put(URI uri, Map<String, List<String>> responseHeaders) {
        Map<String, String> jar = byHost.computeIfAbsent(uri.getHost(), h -> new ConcurrentHashMap<>());
        responseHeaders.forEach((name, values) -> {
            if (name == null || !name.equalsIgnoreCase("Set-Cookie")) return;
            for (String cookie : values) {
                String pair = cookie.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                String lower = cookie.toLowerCase();
                if (value.isEmpty() || lower.contains("max-age=0") || lower.contains("expires=thu, 01 jan 1970")) {
                    jar.remove(key);
                } else {
                    jar.put(key, value);
                }
            }
        });
    }
}
