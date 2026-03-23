package io.cafeai.core.routing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fluent builder for content-type negotiated response dispatch.
 * Used with {@code res.format()} — the Java equivalent of Express's
 * {@code res.format({...})} object literal.
 *
 * <pre>{@code
 *   res.format(ContentMap.of()
 *       .text(() -> res.send("plain text"))
 *       .html(() -> res.send("<b>bold</b>"))
 *       .json(() -> res.json(Map.of("msg", "hello")))
 *       .build());
 * }</pre>
 */
public final class ContentMap {

    private final Map<String, Runnable> handlers = new LinkedHashMap<>();

    private ContentMap() {}

    public static ContentMap of() { return new ContentMap(); }

    public ContentMap text(Runnable handler) { return type("text/plain", handler); }
    public ContentMap html(Runnable handler) { return type("text/html", handler); }
    public ContentMap json(Runnable handler) { return type("application/json", handler); }

    public ContentMap type(String mimeType, Runnable handler) {
        if (handlers.containsKey(mimeType)) {
            throw new IllegalArgumentException(
                "Duplicate content type registration: " + mimeType);
        }
        handlers.put(mimeType, handler);
        return this;
    }

    /** Returns an unmodifiable view of the type-to-handler mappings. */
    public Map<String, Runnable> handlers() {
        return Map.copyOf(handlers);
    }

    public ContentMap build() { return this; }
}
