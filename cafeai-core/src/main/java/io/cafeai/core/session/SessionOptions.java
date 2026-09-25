package io.cafeai.core.session;

import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.routing.CookieOptions;

import java.time.Duration;

/**
 * Options for {@code Middleware.session(SessionStore, SessionOptions)} -- the
 * cookie name, idle timeout, and cookie attributes for the HTTP session
 * cookie. Backend-independent: applies the same whether the store is
 * {@link SessionStore#inMemory()} or {@link SessionStore#sqlite()}.
 *
 * <pre>{@code
 *   app.filter(Middleware.session(SessionStore.sqlite(),
 *       SessionOptions.builder()
 *           .cookieName("sid")
 *           .idleTimeout(Duration.ofHours(2))
 *           .cookieOptions(CookieOptions.builder().secure(true).build())
 *           .build()));
 * }</pre>
 */
public final class SessionOptions {

    /** Name of the cookie that carries the opaque session ID. */
    public static final ConfigKey<String> COOKIE_NAME = ConfigKey.of(
        "cafeai.http.session.cookie.name", String.class, "cafeai.sid",
        "Name of the cookie that carries the opaque HTTP session ID.");

    /** How long a session may be idle before {@code Middleware.session()} treats it as expired. */
    public static final ConfigKey<Duration> IDLE_TIMEOUT = ConfigKey.of(
        "cafeai.http.session.idle.timeout", Duration.class, Duration.ofMinutes(30),
        "How long an HTTP session may be idle before Middleware.session() destroys it.");

    private final String cookieName;
    private final Duration idleTimeout;
    private final CookieOptions cookieOptions;

    private SessionOptions(Builder builder) {
        this.cookieName = builder.cookieName;
        this.idleTimeout = builder.idleTimeout;
        this.cookieOptions = builder.cookieOptions;
    }

    /** Defaults from {@link #COOKIE_NAME}, {@link #IDLE_TIMEOUT}, and {@link CookieOptions} defaults. */
    public static SessionOptions defaults() {
        return builder().build();
    }

    public static Builder builder() { return new Builder(); }

    public String cookieName()          { return cookieName; }
    public Duration idleTimeout()       { return idleTimeout; }
    public CookieOptions cookieOptions() { return cookieOptions; }

    public static final class Builder {
        private String cookieName        = AppConfig.load().get(COOKIE_NAME);
        private Duration idleTimeout     = AppConfig.load().get(IDLE_TIMEOUT);
        private CookieOptions cookieOptions = CookieOptions.builder().build();

        public Builder cookieName(String cookieName) { this.cookieName = cookieName; return this; }
        public Builder idleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; return this; }
        public Builder cookieOptions(CookieOptions cookieOptions) { this.cookieOptions = cookieOptions; return this; }

        public SessionOptions build() { return new SessionOptions(this); }
    }
}
