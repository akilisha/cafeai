package io.cafeai.core.routing;

import java.time.Duration;
import java.time.Instant;

/**
 * Options for setting HTTP cookies via {@code res.cookie()}.
 * Mirrors Express cookie options object.
 */
public final class CookieOptions {

    private Duration maxAge;
    private Instant expires;
    private boolean httpOnly = true;
    private boolean secure = false;
    private SameSite sameSite = SameSite.LAX;
    private String domain;
    private String path = "/";
    private boolean signed = false;

    private CookieOptions() {}

    public static Builder builder() { return new Builder(); }

    public Duration maxAge()      { return maxAge; }
    public Instant expires()      { return expires; }
    public boolean httpOnly()     { return httpOnly; }
    public boolean secure()       { return secure; }
    public SameSite sameSite()    { return sameSite; }
    public String domain()        { return domain; }
    public String path()          { return path; }
    public boolean signed()       { return signed; }

    /** SameSite cookie attribute values. */
    public enum SameSite { STRICT, LAX, NONE }

    public static final class Builder {
        private final CookieOptions opts = new CookieOptions();

        public Builder maxAge(Duration maxAge)     { opts.maxAge = maxAge; return this; }
        public Builder expires(Instant expires)    { opts.expires = expires; return this; }
        public Builder httpOnly(boolean httpOnly)  { opts.httpOnly = httpOnly; return this; }
        public Builder secure(boolean secure)      { opts.secure = secure; return this; }
        public Builder sameSite(SameSite sameSite) { opts.sameSite = sameSite; return this; }
        public Builder domain(String domain)       { opts.domain = domain; return this; }
        public Builder path(String path)           { opts.path = path; return this; }
        public Builder signed(boolean signed)      { opts.signed = signed; return this; }

        public CookieOptions build() { return opts; }
    }
}
