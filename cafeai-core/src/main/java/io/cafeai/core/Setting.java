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
     * When {@code true}, routing is case-sensitive ({@code /Foo} ≠ {@code /foo}).
     * Mirrors Express: {@code app.set('case sensitive routing', false)}
     * Default: {@code false}
     * Type: {@code Boolean}
     */
    CASE_SENSITIVE_ROUTING("case sensitive routing", false, Boolean.class),

    /**
     * When {@code true}, trailing slashes are significant ({@code /foo/} ≠ {@code /foo}).
     * Mirrors Express: {@code app.set('strict routing', false)}
     * Default: {@code false}
     * Type: {@code Boolean}
     */
    STRICT_ROUTING("strict routing", false, Boolean.class),

    /**
     * Number of subdomain segments to remove when calculating {@code req.subdomains()}.
     * Mirrors Express: {@code app.set('subdomain offset', 2)}
     * Default: {@code 2}
     * Type: {@code Integer}
     */
    SUBDOMAIN_OFFSET("subdomain offset", 2, Integer.class),

    // ── Response ──────────────────────────────────────────────────────────────

    /**
     * ETag generation strategy for responses: {@code "weak"}, {@code "strong"},
     * or {@code false} to disable.
     * Mirrors Express: {@code app.set('etag', 'weak')}
     * Default: {@code "weak"}
     * Type: {@code Object} (String or Boolean)
     */
    ETAG("etag", "weak", Object.class),

    /**
     * When {@code true}, HTML characters in {@code res.json()} output are escaped.
     * Mirrors Express: {@code app.set('json escape html', true)}
     * Default: {@code true}
     * Type: {@code Boolean}
     */
    JSON_ESCAPE_HTML("json escape html", true, Boolean.class),

    /**
     * Number of spaces for JSON pretty-printing. {@code 0} disables pretty-printing.
     * Mirrors Express: {@code app.set('json spaces', 0)}
     * Default: {@code 0}
     * Type: {@code Integer}
     */
    JSON_SPACES("json spaces", 0, Integer.class),

    /**
     * Query string parser: {@code "simple"} (built-in) or {@code "extended"} (qs-style).
     * Mirrors Express: {@code app.set('query parser', 'simple')}
     * Default: {@code "simple"}
     * Type: {@code String}
     */
    QUERY_PARSER("query parser", "simple", String.class),

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
    VIEW_ENGINE("view engine", null, String.class),

    /**
     * When {@code true}, compiled view templates are cached after first render.
     * Mirrors Express: {@code app.set('view cache', false)}
     * Default: {@code false} in development, {@code true} in production
     * Type: {@code Boolean}
     */
    VIEW_CACHE("view cache", false, Boolean.class);

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
