package io.cafeai.core;

import java.util.Set;

/**
 * Configuration options for the {@link CafeAI#json()} body-parsing middleware.
 *
 * <p>Mirrors Express {@code express.json([options])} exactly.
 *
 * <pre>{@code
 *   app.filter(CafeAI.json());  // defaults -- good for most applications
 *
 *   app.filter(CafeAI.json(JsonOptions.builder()
 *       .limit(512 * 1024)     // 512 KB max body
 *       .strict(true)          // reject primitives at root
 *       .inflate(true)         // accept gzip/deflate
 *       .build()));
 * }</pre>
 */
public final class JsonOptions {

    /** Default body size limit: 100 KB. Mirrors Express default. */
    public static final long DEFAULT_LIMIT = 100 * 1024L;

    private final boolean inflate;
    private final long    limit;
    private final boolean strict;
    private final Set<String> type;
    private final boolean verify;

    private JsonOptions(Builder b) {
        this.inflate = b.inflate;
        this.limit   = b.limit;
        this.strict  = b.strict;
        this.type    = Set.copyOf(b.type);
        this.verify  = b.verify;
    }

    /** Default options -- matches Express defaults. */
    public static JsonOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether to inflate compressed (gzip / deflate) bodies.
     * Mirrors Express {@code inflate}. Default: {@code true}.
     */
    public boolean inflate() { return inflate; }

    /**
     * Maximum request body size in bytes.
     * Bodies exceeding this are rejected with HTTP 413.
     * Mirrors Express {@code limit}. Default: 100 KB.
     */
    public long limit() { return limit; }

    /**
     * When {@code true}, only JSON objects ({@code {}}) and arrays ({@code []})
     * are accepted as root values. Primitives ({@code "string"}, {@code 42})
     * are rejected with HTTP 400.
     * Mirrors Express {@code strict}. Default: {@code true}.
     */
    public boolean strict() { return strict; }

    /**
     * Set of {@code Content-Type} values this middleware will attempt to parse.
     * Mirrors Express {@code type}. Default: {@code ["application/json"]}.
     */
    public Set<String> type() { return type; }

    /**
     * Whether to call a verification function after parsing.
     * Full verify function support in ROADMAP-01 Phase 2 extension.
     * Mirrors Express {@code verify}. Default: {@code false}.
     */
    public boolean verify() { return verify; }

    public static final class Builder {
        private boolean      inflate = true;
        private long         limit   = DEFAULT_LIMIT;
        private boolean      strict  = true;
        private Set<String>  type    = Set.of("application/json");
        private boolean      verify  = false;

        public Builder inflate(boolean inflate) { this.inflate = inflate; return this; }
        public Builder limit(long limit)         { this.limit   = limit;   return this; }
        public Builder strict(boolean strict)    { this.strict  = strict;  return this; }
        public Builder type(String... types)     { this.type    = Set.of(types); return this; }
        public Builder verify(boolean verify)    { this.verify  = verify;  return this; }

        public JsonOptions build() { return new JsonOptions(this); }
    }
}
