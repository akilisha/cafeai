package io.cafeai.core;

/**
 * Configuration options for the {@link CafeAI#urlencoded()} body-parsing middleware.
 *
 * <p>Mirrors Express {@code express.urlencoded([options])}.
 *
 * <pre>{@code
 *   app.filter(CafeAI.urlencoded());
 *
 *   app.filter(CafeAI.urlencoded(UrlEncodedOptions.builder()
 *       .extended(true)
 *       .limit(256 * 1024)
 *       .build()));
 * }</pre>
 */
public final class UrlEncodedOptions {

    /** Default body size limit: 100 KB. */
    public static final long DEFAULT_LIMIT = 100 * 1024L;

    private final boolean inflate;
    private final long    limit;
    private final boolean extended;

    private UrlEncodedOptions(Builder b) {
        this.inflate  = b.inflate;
        this.limit    = b.limit;
        this.extended = b.extended;
    }

    public static UrlEncodedOptions defaults() { return builder().build(); }
    public static Builder builder()            { return new Builder(); }

    /**
     * Whether to inflate gzip/deflate encoded bodies. Default: {@code true}.
     */
    public boolean inflate()  { return inflate; }

    /**
     * Maximum body size in bytes. Default: 100 KB.
     */
    public long limit()       { return limit; }

    /**
     * When {@code true}, allows rich objects and arrays using the {@code qs} library
     * format (nested keys via {@code a[b]=c}). When {@code false}, only flat
     * key-value pairs are parsed via the simple {@code querystring} format.
     *
     * <p>Mirrors Express {@code extended}. Default: {@code false}.
     */
    public boolean extended() { return extended; }

    public static final class Builder {
        private boolean inflate  = true;
        private long    limit    = DEFAULT_LIMIT;
        private boolean extended = false;

        public Builder inflate(boolean inflate)   { this.inflate  = inflate;  return this; }
        public Builder limit(long limit)           { this.limit    = limit;    return this; }
        public Builder extended(boolean extended)  { this.extended = extended; return this; }

        public UrlEncodedOptions build() { return new UrlEncodedOptions(this); }
    }
}
