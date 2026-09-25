package io.cafeai.core.middleware;

import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.internal.BuiltInMiddleware;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import io.cafeai.core.session.SessionOptions;
import io.cafeai.core.session.SessionStore;

import java.util.List;

/**
 * The fundamental unit of composability in CafeAI. (ADR-002, ADR-009)
 *
 * <p>Everything is a {@code Middleware}: HTTP concerns, AI concerns, security,
 * observability, guardrails, route handlers. No other handler type exists.
 * A terminating route handler is a {@code Middleware} that does not call
 * {@code next.run()}. An inline pipeline middleware does. No other distinction.
 *
 * <p>Mirrors Express: {@code function(req, res, next)}
 *
 * <p>Registering middleware:
 * <pre>{@code
 *   // Cross-cutting pre-processing -- runs before route dispatch (app.filter):
 *   app.filter(Middleware.requestLogger());
 *   app.filter(CafeAI.json());
 *   app.filter("/api", Middleware.cors());
 *
 *   // Per-route inline pipeline (variadic handlers):
 *   app.get("/users/:id", authenticate, authorize("admin"),
 *       (req, res, next) -> res.json(userService.find(req.params("id"))));
 *
 *   // Post-processing -- code after next.run() runs after the full downstream
 *   // chain completes (blocks on virtual thread -- no async needed):
 *   app.filter((req, res, next) -> {
 *       long start = System.nanoTime();
 *       next.run();
 *       log.info("{}ms", (System.nanoTime() - start) / 1_000_000);
 *   });
 * }</pre>
 */
@FunctionalInterface
public interface Middleware {

    /**
     * A no-op middleware that immediately calls {@code next.run()}.
     * Used as a sentinel for empty handler arrays and sub-router placeholders.
     */
    Middleware NOOP = (req, res, next) -> next.run();

    /**
     * Executes this middleware.
     *
     * <p>Call {@code next.run()} to pass control to the next middleware in the chain.
     * Not calling {@code next.run()} terminates the chain -- no further middleware
     * or route handler will execute for this request.
     *
     * <p>Code written after {@code next.run()} executes after the entire downstream
     * chain completes (post-processing). This works naturally on virtual threads
     * because {@code next.run()} is a real blocking call.
     *
     * @param req  the incoming request
     * @param res  the outgoing response
     * @param next call {@code next.run()} to continue, {@code next.fail(err)} for errors
     */
    void handle(Request req, Response res, Next next);

    /**
     * Composes this middleware with {@code other} -- {@code this} runs first, then
     * {@code other} runs when {@code this} calls {@code next.run()}.
     *
     * <p>This is the primitive used by {@code CafeAIApp.compose()} to build the
     * per-route handler chain from variadic middleware arrays:
     * <pre>{@code
     *   // app.get("/x", mw1, mw2, mw3) composes as:
     *   Middleware chain = mw1.then(mw2.then(mw3));
     * }</pre>
     */
    default Middleware then(Middleware other) {
        return (req, res, next) ->
            this.handle(req, res, () -> {
                try {
                    other.handle(req, res, next);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    // -- Built-in Middleware Factory Methods -----------------------------------

    /**
     * JSON body parsing middleware. Parses {@code application/json} bodies into
     * {@code req.body()}. Register via {@code app.filter()} for global pre-processing.
     * Mirrors Express: {@code express.json()}
     */
    static Middleware json() {
        return BuiltInMiddleware.json();
    }

    /**
     * CORS headers middleware -- permissive, for development.
     */
    static Middleware cors() {
        return BuiltInMiddleware.cors();
    }

    /**
     * Structured request/response logging middleware.
     */
    static Middleware requestLogger() {
        return BuiltInMiddleware.requestLogger();
    }

    /**
     * Per-IP rate limiting middleware. Returns 429 on breach.
     *
     * @param requestsPerMinute maximum requests per IP per minute
     */
    static Middleware rateLimit(int requestsPerMinute) {
        return BuiltInMiddleware.rateLimit(requestsPerMinute);
    }

    /**
     * Per-session LLM token budget middleware. Returns 429 on exhaustion.
     *
     * @param maxTokensPerSession maximum tokens allowed per session
     */
    static Middleware tokenBudget(int maxTokensPerSession) {
        return BuiltInMiddleware.tokenBudget(maxTokensPerSession);
    }

    /**
     * HTTP session middleware -- attaches a {@code Session} to {@code req.session()},
     * backed by {@code store}. Mirrors Express {@code session(store)}.
     *
     * <p>Not the AI-conversation "session" ({@code MemoryStrategy}) -- see
     * {@code io.cafeai.core.session.Session}'s Javadoc for the disambiguation.
     *
     * <pre>{@code
     *   app.filter(Middleware.session(SessionStore.sqlite()));
     * }</pre>
     *
     * @param store the backing session store -- {@code SessionStore.inMemory()} for
     *              dev/test, {@code SessionStore.sqlite()} for real deployments
     */
    static Middleware session(SessionStore store) {
        return BuiltInMiddleware.session(store, SessionOptions.defaults());
    }

    /** {@link #session(SessionStore)} with explicit cookie/timeout options. */
    static Middleware session(SessionStore store, SessionOptions options) {
        return BuiltInMiddleware.session(store, options);
    }

    /** Largest signed cookie-session payload {@link #cookieSession(String)} will emit. */
    ConfigKey<Integer> MAX_COOKIE_SESSION_BYTES = ConfigKey.of(
        "cafeai.http.session.cookie.maxBytes", Integer.class, 4093,
        "Largest encoded+signed payload Middleware.cookieSession(...) will write to a cookie, " +
        "in bytes. 4093 leaves headroom under the ~4096-byte single-cookie limit most browsers guarantee.");

    /**
     * Stateless HTTP session middleware -- the whole session is signed and stored
     * in the cookie itself; no server-side store. Mirrors Express {@code cookie-session},
     * as opposed to {@link #session(SessionStore)} (Express {@code express-session}).
     *
     * <p>{@code secret} is the HMAC-SHA256 signing key. Use a long, random value --
     * signing proves the cookie wasn't tampered with, it does not hide its contents
     * (the client can read every attribute; do not put secrets in the session itself).
     *
     * <pre>{@code
     *   app.filter(Middleware.cookieSession(System.getenv("SESSION_SECRET")));
     * }</pre>
     */
    static Middleware cookieSession(String secret) {
        return cookieSession(List.of(secret), SessionOptions.defaults());
    }

    /** {@link #cookieSession(String)} with explicit cookie/timeout options. */
    static Middleware cookieSession(String secret, SessionOptions options) {
        return cookieSession(List.of(secret), options);
    }

    /**
     * {@link #cookieSession(String, SessionOptions)} with secret rotation: cookies are
     * signed with {@code secrets.get(0)} and verified against any of {@code secrets},
     * so an old secret keeps validating existing cookies while a new one phases in.
     */
    static Middleware cookieSession(List<String> secrets, SessionOptions options) {
        return BuiltInMiddleware.cookieSession(secrets, options);
    }

    /** Thrown when a session's encoded, signed payload exceeds {@link #MAX_COOKIE_SESSION_BYTES}. */
    class CookieSessionTooLargeException extends RuntimeException {
        public CookieSessionTooLargeException(String message) { super(message); }
    }

    /**
     * Stateless HTTP session middleware with confidentiality: the session is
     * AES-GCM encrypted into the cookie, not just signed. Use this instead of
     * {@link #cookieSession(String)} when the session may hold data the client
     * itself shouldn't be able to read (cookieSession is tamper-proof but not
     * confidential -- the client can read every attribute in plain text).
     *
     * <p>{@code secret} is hashed (SHA-256) into the AES-256 key -- same
     * high-entropy-secret expectation as {@link #cookieSession(String)}, not a
     * password (no password-based key stretching is applied).
     *
     * <pre>{@code
     *   app.filter(Middleware.encryptedCookieSession(System.getenv("SESSION_KEY")));
     * }</pre>
     */
    static Middleware encryptedCookieSession(String secret) {
        return encryptedCookieSession(List.of(secret), SessionOptions.defaults());
    }

    /** {@link #encryptedCookieSession(String)} with explicit cookie/timeout options. */
    static Middleware encryptedCookieSession(String secret, SessionOptions options) {
        return encryptedCookieSession(List.of(secret), options);
    }

    /**
     * {@link #encryptedCookieSession(String, SessionOptions)} with key rotation:
     * encrypted with {@code secrets.get(0)}, decryptable with any of {@code secrets}.
     */
    static Middleware encryptedCookieSession(List<String> secrets, SessionOptions options) {
        return BuiltInMiddleware.encryptedCookieSession(secrets, options);
    }
}
