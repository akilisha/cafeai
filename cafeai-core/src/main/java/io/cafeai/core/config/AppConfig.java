package io.cafeai.core.config;

import io.cafeai.core.spi.ConfigProvider;

import java.time.Duration;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Resolves a {@link ConfigKey} to its value: an override if one was
 * supplied, otherwise the key's own coded default. Never throws for a
 * missing key — that's the point of the default living on the key itself.
 *
 * <pre>{@code
 *   static final ConfigKey<Duration> CHAT_TIMEOUT = ConfigKey.of(
 *       "cafeai.chat.timeout", Duration.class, Duration.ofSeconds(60), "...");
 *
 *   Duration timeout = AppConfig.load().get(CHAT_TIMEOUT);
 * }</pre>
 *
 * <p><strong>{@code cafeai-core} resolves defaults; {@code cafeai-config}
 * resolves everything else.</strong> {@link #load()} looks for a
 * {@link ConfigProvider} via {@link ServiceLoader}; if {@code cafeai-config}
 * is on the classpath, resolution — system properties, environment
 * variables, configuration files, whatever sources that module composes —
 * is entirely its job, using Helidon Config's own mapping between a dotted
 * key and an environment variable. If {@code cafeai-config} is absent,
 * {@code load()} returns the {@link ConfigKey}'s coded default, unconditionally
 * — the exact behavior every one of these values had before it was a
 * {@code ConfigKey} at all. Declaring a value as a {@code ConfigKey} is what
 * gives it a real path to being overridden; it never risks a working
 * default disappearing.
 *
 * <p><strong>Config supplies values, never wires capabilities.</strong> A key
 * can say what a timeout is; it never causes {@code app.ai(...)} or any other
 * registration call to happen on its own. That boundary is deliberate.
 */
@FunctionalInterface
public interface AppConfig {

    /**
     * The raw string value for this key from whatever source this
     * {@code AppConfig} resolves against, or empty if not set there.
     */
    Optional<String> getRaw(ConfigKey<?> key);

    /**
     * Resolves a typed value: the raw string (parsed to the key's type) if
     * set anywhere this {@code AppConfig} checks, otherwise the key's own
     * default.
     *
     * @throws IllegalArgumentException if a value was found but doesn't parse
     *         as the key's type
     */
    default <T> T get(ConfigKey<T> key) {
        return getRaw(key).map(raw -> parse(key, raw)).orElseGet(key::defaultValue);
    }

    /**
     * Reads {@code key} and passes it to {@code use}, which is normally a setter that validates its
     * argument. A value that setter refuses is reported with the name of the key it came from, so a
     * bad setting says which setting it was.
     *
     * @throws IllegalArgumentException naming the key, if {@code use} refuses the configured value
     */
    default <T, R> R apply(ConfigKey<T> key, java.util.function.Function<? super T, R> use) {
        T value = get(key);
        try {
            return use.apply(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid value for " + key.name() + ": \"" + value + "\" (" + e.getMessage() + ")", e);
        }
    }

    /** {@link #get} for a count or size that must be at least 1. */
    default int positive(ConfigKey<Integer> key) {
        return apply(key, value -> {
            if (value < 1) throw new IllegalArgumentException("must be at least 1");
            return value;
        });
    }

    /** {@link #get} for a long count or size that must be at least 1. */
    default long positiveLong(ConfigKey<Long> key) {
        return apply(key, value -> {
            if (value < 1) throw new IllegalArgumentException("must be at least 1");
            return value;
        });
    }

    /** {@link #get} for a duration that must be longer than zero. */
    default java.time.Duration positiveDuration(ConfigKey<java.time.Duration> key) {
        return apply(key, value -> {
            if (value.isZero() || value.isNegative()) throw new IllegalArgumentException("must be longer than zero");
            return value;
        });
    }

    /**
     * {@link ConfigProvider} via {@link ServiceLoader} when {@code cafeai-config}
     * is present; otherwise an {@code AppConfig} that resolves nothing, so
     * {@link #get} always returns the {@link ConfigKey}'s coded default.
     */
    static AppConfig load() {
        return ServiceLoader.load(ConfigProvider.class)
            .findFirst()
            .<AppConfig>map(ConfigProvider::config)
            .orElse(key -> Optional.empty());
    }

    private static <T> T parse(ConfigKey<T> key, String raw) {
        Class<T> type = key.type();
        try {
            if (type == String.class)   return type.cast(raw);
            if (type == Integer.class)  return type.cast(Integer.valueOf(raw.trim()));
            if (type == Long.class)     return type.cast(Long.valueOf(raw.trim()));
            if (type == Double.class)   return type.cast(Double.valueOf(raw.trim()));
            if (type == Boolean.class)  return type.cast(Boolean.valueOf(raw.trim()));
            if (type == Duration.class) return type.cast(parseDuration(raw.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Invalid value for " + key.name() + ": \"" + raw
                    + "\" is not a valid " + type.getSimpleName(), e);
        }
        throw new IllegalArgumentException("Unsupported config value type: " + type);
    }

    /** Plain seconds ("60") or a suffixed amount ("60s", "5m", "2h"). */
    private static Duration parseDuration(String raw) {
        if (raw.matches("\\d+")) {
            return Duration.ofSeconds(Long.parseLong(raw));
        }
        char unit = raw.charAt(raw.length() - 1);
        long amount;
        try {
            amount = Long.parseLong(raw.substring(0, raw.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid duration: \"" + raw + "\"", e);
        }
        return switch (unit) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException(
                "Unrecognised duration suffix in \"" + raw + "\" — use s, m, or h");
        };
    }
}
