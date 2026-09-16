package io.cafeai.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every {@link ConfigKey} declared so far, in declaration order.
 *
 * <p>Populated automatically — a key registers itself when constructed via
 * {@link ConfigKey#of}. There is no separate registration step and nothing
 * to keep in sync by hand.
 *
 * <p><strong>Not exhaustive at any given moment.</strong> A key only appears
 * here once the class that declares it has been loaded by the JVM. Read this
 * after the application has been running for a while — or log it at the end
 * of {@code app.listen()} — not at the very first line of {@code main()}.
 */
public final class ConfigCatalog {

    private static final Map<String, ConfigKey<?>> KEYS = new LinkedHashMap<>();

    private ConfigCatalog() {}

    static synchronized void register(ConfigKey<?> key) {
        KEYS.put(key.name(), key);
    }

    /** A snapshot of every key registered so far, in declaration order. */
    public static synchronized Map<String, ConfigKey<?>> known() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(KEYS));
    }
}
