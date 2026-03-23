package io.cafeai.core;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Configuration options for the {@link CafeAI#serveStatic(String)} middleware.
 *
 * <p>Mirrors Express {@code express.static(root, [options])} exactly.
 *
 * <pre>{@code
 *   // Minimal — serve from "public/" directory
 *   app.use(CafeAI.serveStatic("public"));
 *
 *   // Production — ETags, max-age, dotfile protection
 *   app.use(CafeAI.serveStatic("public", StaticOptions.builder()
 *       .maxAge(Duration.ofDays(7))
 *       .etag(true)
 *       .dotfiles(StaticOptions.Dotfiles.IGNORE)
 *       .extensions(List.of("html", "htm"))
 *       .build()));
 * }</pre>
 */
public final class StaticOptions {

    private final Duration      maxAge;
    private final boolean       etag;
    private final String        index;
    private final Dotfiles      dotfiles;
    private final boolean       redirect;
    private final boolean       fallthrough;
    private final boolean       immutable;
    private final boolean       cacheControl;
    private final boolean       acceptRanges;
    private final boolean       lastModified;
    private final List<String>  extensions;

    private StaticOptions(Builder b) {
        this.maxAge      = b.maxAge;
        this.etag        = b.etag;
        this.index       = b.index;
        this.dotfiles    = b.dotfiles;
        this.redirect    = b.redirect;
        this.fallthrough = b.fallthrough;
        this.immutable   = b.immutable;
        this.cacheControl= b.cacheControl;
        this.acceptRanges= b.acceptRanges;
        this.lastModified= b.lastModified;
        this.extensions  = List.copyOf(b.extensions);
    }

    public static StaticOptions defaults() { return builder().build(); }
    public static Builder builder()        { return new Builder(); }

    /**
     * How dotfiles (files/dirs starting with {@code .}) are handled.
     * Mirrors Express {@code dotfiles} option.
     */
    public enum Dotfiles {
        /** Serve dotfiles like any other file. */
        ALLOW,
        /** Return 403 for dotfile requests. */
        DENY,
        /** Pretend they don't exist — 404 (or next()). Default. */
        IGNORE
    }

    public Duration     maxAge()       { return maxAge; }
    public boolean      etag()         { return etag; }
    public String       index()        { return index; }
    public Dotfiles     dotfiles()     { return dotfiles; }
    public boolean      redirect()     { return redirect; }
    public boolean      fallthrough()  { return fallthrough; }
    public boolean      immutable()    { return immutable; }
    public boolean      cacheControl() { return cacheControl; }
    public boolean      acceptRanges() { return acceptRanges; }
    public boolean      lastModified() { return lastModified; }
    public List<String> extensions()   { return extensions; }

    public static final class Builder {
        private Duration      maxAge       = Duration.ZERO;
        private boolean       etag         = true;
        private String        index        = "index.html";
        private Dotfiles      dotfiles     = Dotfiles.IGNORE;
        private boolean       redirect     = true;
        private boolean       fallthrough  = true;
        private boolean       immutable    = false;
        private boolean       cacheControl = true;
        private boolean       acceptRanges = true;
        private boolean       lastModified = true;
        private List<String>  extensions   = List.of();

        public Builder maxAge(Duration d)          { this.maxAge       = d;           return this; }
        public Builder etag(boolean e)             { this.etag         = e;           return this; }
        public Builder index(String i)             { this.index        = i;           return this; }
        public Builder dotfiles(Dotfiles d)        { this.dotfiles     = d;           return this; }
        public Builder redirect(boolean r)         { this.redirect     = r;           return this; }
        public Builder fallthrough(boolean f)      { this.fallthrough  = f;           return this; }
        public Builder immutable(boolean i)        { this.immutable    = i;           return this; }
        public Builder cacheControl(boolean c)     { this.cacheControl = c;           return this; }
        public Builder acceptRanges(boolean a)     { this.acceptRanges = a;           return this; }
        public Builder lastModified(boolean l)     { this.lastModified = l;           return this; }
        public Builder extensions(List<String> e)  { this.extensions   = List.copyOf(e); return this; }

        public StaticOptions build() { return new StaticOptions(this); }
    }
}
