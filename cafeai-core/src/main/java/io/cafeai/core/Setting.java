package io.cafeai.core;

/**
 * Typed application settings — replaces Express's string-keyed {@code app.set(name, value)}.
 *
 * <p>Every setting has an explicit Java type, a default value matching Express, and
 * a clear name. No magic strings, no silent typos, full IDE autocomplete.
 *
 * <p>ADR-005 translation: Express uses {@code app.set('trust proxy', true)}.
 * CafeAI uses {@code app.set(Setting.TRUST_PROXY, true)}. Same semantics, type-safe.
 *
 * <pre>{@code
 *   app.set(Setting.ENV, "production");
 *   app.enable(Setting.TRUST_PROXY);
 *   app.disable(Setting.X_POWERED_BY);
 *
 *   String env = app.setting(Setting.ENV, String.class); // → "production"
 *   boolean trusted = app.enabled(Setting.TRUST_PROXY);  // → true
 * }</pre>
 */
public enum Setting {

    // ── Environment ───────────────────────────────────────────────────────────

    /**
     * Application environment name.
     * Mirrors Express: {@code app.set('env', 'production')}
     * Default: {@code "development"}
     * Type: {@code String}
     */
    ENV("env", "development", String.class),

    // ── Security ──────────────────────────────────────────────────────────────

    /**
     * When {@code true}, the leftmost IP in {@code X-Forwarded-For} is used as
     * {@code req.ip()}. Accepts {@code true}, {@code false}, or a hop count.
     * Mirrors Express: {@code app.set('trust proxy', true)}
     * Default: {@code false}
     * Type: {@code Object} (Boolean or Integer hop count)
     */
    TRUST_PROXY("trust proxy", false, Object.class),

    /**
     * When {@code true}, sends {@code X-Powered-By: CafeAI} response header.
     * Mirrors Express: {@code app.set('x-powered-by', true)}
     * Default: {@code true}
     * Type: {@code Boolean}
     */
    X_POWERED_BY("x-powered-by", true, Boolean.class),

    // ── Routing ───────────────────────────────────────────────────────────────

    /**
     * Number of subdomain segments to remove when calculating {@code req.subdomains()}.
     * Mirrors Express: {@code app.set('subdomain offset', 2)}
     * Default: {@code 2}
     * Type: {@code Integer}
     */
    SUBDOMAIN_OFFSET("subdomain offset", 2, Integer.class),

    // ── Response ──────────────────────────────────────────────────────────────

    /**
     * Number of spaces for JSON pretty-printing. {@code 0} disables pretty-printing.
     * Mirrors Express: {@code app.set('json spaces', 0)}
     * Default: {@code 0}
     * Type: {@code Integer}
     */
    JSON_SPACES("json spaces", 0, Integer.class),

    // ── Views ─────────────────────────────────────────────────────────────────

    /**
     * Directory containing view templates. Absolute or relative to the working directory.
     * Mirrors Express: {@code app.set('views', './views')}
     * Default: {@code "views"}
     * Type: {@code String}
     */
    VIEWS("views", "views", String.class),

    /**
     * Default template engine extension (e.g. {@code "html"}, {@code "mustache"}).
     * Used when no extension is specified in {@code res.render()}.
     * Mirrors Express: {@code app.set('view engine', ...)}
     * Default: {@code null} (must be set explicitly)
     * Type: {@code String}
     */
    VIEW_ENGINE("view engine", null, String.class);

    // ── Metadata ──────────────────────────────────────────────────────────────

    private final String   expressName;
    private final Object   defaultValue;
    private final Class<?> valueType;

    Setting(String expressName, Object defaultValue, Class<?> valueType) {
        this.expressName  = expressName;
        this.defaultValue = defaultValue;
        this.valueType    = valueType;
    }

    /** The Express string name for this setting. For documentation reference only. */
    public String expressName()  { return expressName; }

    /** The default value for this setting, matching Express defaults. */
    public Object defaultValue() { return defaultValue; }

    /** The expected Java type for this setting's value. */
    public Class<?> valueType()  { return valueType; }

    /**
     * Returns {@code true} if this setting holds a boolean value.
     * Only boolean settings support {@code app.enable()} and {@code app.disable()}.
     */
    public boolean isBoolean() {
        return valueType == Boolean.class;
    }
}
