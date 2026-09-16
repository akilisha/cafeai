package io.cafeai.core.config;

import java.util.Locale;
import java.util.Objects;

/**
 * A self-documenting configuration key: name, type, default value, and a
 * one-line description, declared once at the point of use.
 *
 * <p>Declare it right next to the code that reads it — not in a central
 * catalog file. The declaration <em>is</em> the documentation, so it can't
 * drift out of sync with what the code actually does the way a hand-written
 * reference doc can:
 *
 * <pre>{@code
 *   static final ConfigKey<Duration> CHAT_TIMEOUT = ConfigKey.of(
 *       "cafeai.chat.timeout", Duration.class, Duration.ofSeconds(60),
 *       "Timeout for a single LLM chat call, any provider.");
 *
 *   Duration timeout = AppConfig.load().get(CHAT_TIMEOUT);
 * }</pre>
 *
 * <p>Constructing a {@code ConfigKey} self-registers it into
 * {@link ConfigCatalog} — the same "declaring it is registering it" idiom
 * {@link io.cafeai.core.spi.CafeAIModule} uses. The catalog is therefore only
 * complete once every class that declares a key has actually been loaded by
 * the JVM; a startup-time dump won't show keys from code paths that haven't
 * run yet.
 *
 * <p>Supported types: {@code String}, {@code Integer}, {@code Long},
 * {@code Double}, {@code Boolean}, {@code java.time.Duration} (accepts plain
 * seconds — {@code "60"} — or a suffixed form — {@code "60s"}, {@code "5m"},
 * {@code "2h"}).
 *
 * @param <T> the value's type
 */
public final class ConfigKey<T> {

    private final String name;
    private final Class<T> type;
    private final T defaultValue;
    private final String description;

    private ConfigKey(String name, Class<T> type, T defaultValue, String description) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.description = Objects.requireNonNull(description, "description");
        ConfigCatalog.register(this);
    }

    /**
     * Declares a configuration key. Dotted, lowercase names are the
     * convention — {@code "cafeai.rag.chunk.size"} — since {@link #envVarName()}
     * derives the environment-variable form from it.
     */
    public static <T> ConfigKey<T> of(String name, Class<T> type, T defaultValue, String description) {
        return new ConfigKey<>(name, type, defaultValue, description);
    }

    public String name() { return name; }
    public Class<T> type() { return type; }
    public T defaultValue() { return defaultValue; }
    public String description() { return description; }

    /**
     * The environment-variable form of this key — dots become underscores,
     * uppercased: {@code "cafeai.rag.chunk.size"} → {@code "CAFEAI_RAG_CHUNK_SIZE"}.
     * One logical key, nameable either way regardless of which layer it's set in.
     */
    public String envVarName() {
        return name.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    @Override
    public String toString() {
        return name + " (" + type.getSimpleName() + ", default " + defaultValue + ")";
    }
}
