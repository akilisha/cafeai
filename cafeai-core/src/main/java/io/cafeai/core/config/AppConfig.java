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
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #ambient()} — system properties, then environment variables.
 *       Zero dependencies; works with {@code cafeai-core} alone.</li>
 *   <li>{@link #load()} — {@code ambient()}, then falls through to
 *       {@code cafeai-config}'s file/profile layer when that module is on
 *       the classpath. This is what application and framework code should
 *       call; a library module never needs to know or check which one it
 *       got.</li>
 * </ul>
 *
 * <p>Precedence, highest to lowest: system property, environment variable,
 * active-profile properties file, default properties file, the
 * {@link ConfigKey}'s own coded default.
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
     * System properties, then environment variables. No files, no profiles —
     * works with {@code cafeai-core} alone, zero extra dependency.
     */
    static AppConfig ambient() {
        return key -> {
            String sysProp = System.getProperty(key.name());
            if (sysProp != null) {
                return Optional.of(sysProp);
            }
            return Optional.ofNullable(System.getenv(key.envVarName()));
        };
    }

    /**
     * {@link #ambient()} first; if neither a system property nor an
     * environment variable is set, falls through to {@code cafeai-config}'s
     * {@link ConfigProvider} when that module is present. Falls back to
     * {@code ambient()} alone otherwise.
     */
    static AppConfig load() {
        AppConfig ambient = ambient();
        return ServiceLoader.load(ConfigProvider.class)
            .findFirst()
            .<AppConfig>map(provider -> key -> {
                Optional<String> fromAmbient = ambient.getRaw(key);
                return fromAmbient.isPresent() ? fromAmbient : provider.config().getRaw(key);
            })
            .orElse(ambient);
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
