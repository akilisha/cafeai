package io.cafeai.core;

import java.util.Set;

/**
 * Configuration options for the {@link CafeAI#raw()} body-parsing middleware.
 *
 * <p>Mirrors Express {@code express.raw([options])}.
 *
 * <pre>{@code
 *   app.filter(CafeAI.raw());
 *
 *   app.filter(CafeAI.raw(RawOptions.builder()
 *       .limit(10 * 1024 * 1024)  // 10 MB max
 *       .type("application/octet-stream", "application/pdf")
 *       .build()));
 * }</pre>
 */
public final class RawOptions {

    /** Default body size limit: 100 KB. */
    public static final long DEFAULT_LIMIT = 100 * 1024L;

    private final boolean    inflate;
    private final long       limit;
    private final Set<String> type;

    private RawOptions(Builder b) {
        this.inflate = b.inflate;
        this.limit   = b.limit;
        this.type    = Set.copyOf(b.type);
    }

    public static RawOptions defaults() { return builder().build(); }
    public static Builder builder()     { return new Builder(); }

    public boolean     inflate() { return inflate; }
    public long        limit()   { return limit; }
    public Set<String> type()    { return type; }

    public static final class Builder {
        private boolean     inflate = true;
        private long        limit   = DEFAULT_LIMIT;
        private Set<String> type    = Set.of("application/octet-stream");

        public Builder inflate(boolean inflate) { this.inflate = inflate; return this; }
        public Builder limit(long limit)         { this.limit   = limit;   return this; }
        public Builder type(String... types)     { this.type    = Set.of(types); return this; }

        public RawOptions build() { return new RawOptions(this); }
    }
}
