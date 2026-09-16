package io.cafeai.core.config;

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
 * <p>The name is a dotted key, same style as Spring or Helidon —
 * {@code "cafeai.rag.chunk.size"}. Nothing in {@code cafeai-core} ever
 * derives a second spelling from it (an environment-variable form, a
 * system-property form, or otherwise); there is exactly one name. Whether,
 * and how, that name maps onto an environment variable is entirely
 * {@code cafeai-config}'s concern, resolved by Helidon Config's own
 * established mapping — not a convention CafeAI invents or owns.
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

    /** Declares a configuration key. Dotted names are the convention — {@code "cafeai.rag.chunk.size"}. */
    public static <T> ConfigKey<T> of(String name, Class<T> type, T defaultValue, String description) {
        return new ConfigKey<>(name, type, defaultValue, description);
    }

    public String name() { return name; }
    public Class<T> type() { return type; }
    public T defaultValue() { return defaultValue; }
    public String description() { return description; }

    @Override
    public String toString() {
        return name + " (" + type.getSimpleName() + ", default " + defaultValue + ")";
    }
}
