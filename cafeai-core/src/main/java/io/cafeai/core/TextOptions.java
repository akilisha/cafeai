package io.cafeai.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Configuration options for the {@link CafeAI#text()} body-parsing middleware.
 *
 * <p>Mirrors Express {@code express.text([options])}.
 *
 * <pre>{@code
 *   app.filter(CafeAI.text());
 *
 *   app.filter(CafeAI.text(TextOptions.builder()
 *       .defaultCharset(StandardCharsets.UTF_8)
 *       .limit(256 * 1024)
 *       .type("text/plain", "text/csv")
 *       .build()));
 * }</pre>
 */
public final class TextOptions {

    /** Default body size limit: 100 KB. */
    public static final long DEFAULT_LIMIT = 100 * 1024L;

    private final boolean    inflate;
    private final long       limit;
    private final Charset    defaultCharset;
    private final Set<String> type;

    private TextOptions(Builder b) {
        this.inflate        = b.inflate;
        this.limit          = b.limit;
        this.defaultCharset = b.defaultCharset;
        this.type           = Set.copyOf(b.type);
    }

    public static TextOptions defaults() { return builder().build(); }
    public static Builder builder()      { return new Builder(); }

    public boolean     inflate()        { return inflate; }
    public long        limit()          { return limit; }
    public Charset     defaultCharset() { return defaultCharset; }
    public Set<String> type()           { return type; }

    public static final class Builder {
        private boolean     inflate        = true;
        private long        limit          = DEFAULT_LIMIT;
        private Charset     defaultCharset = StandardCharsets.UTF_8;
        private Set<String> type           = Set.of("text/plain");

        public Builder inflate(boolean inflate)         { this.inflate        = inflate;        return this; }
        public Builder limit(long limit)                { this.limit          = limit;           return this; }
        public Builder defaultCharset(Charset charset)  { this.defaultCharset = charset;         return this; }
        public Builder type(String... types)            { this.type           = Set.of(types);   return this; }

        public TextOptions build() { return new TextOptions(this); }
    }
}
