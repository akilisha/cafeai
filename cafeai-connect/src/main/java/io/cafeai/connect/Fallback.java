package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defines what CafeAI does when an out-of-process service is unavailable
 * at startup or during a health probe.
 *
 * <p>This is operational intelligence -- the application's policy for
 * capability degradation. Not just "where is the service" but "what
 * does this application do when it isn't there."
 *
 * <pre>{@code
 *   // Log a warning and continue -- service may come up later
 *   app.connect(Redis.at("redis:6379")
 *       .onUnavailable(Fallback.warnAndContinue()));
 *
 *   // Abort startup -- this service is non-negotiable
 *   app.connect(PgVector.at("jdbc:postgresql://pgvector/cafeai")
 *       .onUnavailable(Fallback.failFast()));
 *
 *   // Use a different provider if Ollama isn't running locally
 *   app.connect(Ollama.at("http://ollama:11434")
 *       .onUnavailable(Fallback.use(OpenAI.gpt4o())));
 *
 *   // Register a fallback connection -- try Chroma if pgvector is down
 *   app.connect(PgVector.at("jdbc:...")
 *       .onUnavailable(Fallback.connectInstead(Chroma.at("http://chroma:8000"))));
 * }</pre>
 */
@FunctionalInterface
public interface Fallback {

    /**
     * Called when a connection's probe returns {@link HealthStatus.State#UNREACHABLE}
     * or {@link HealthStatus.State#DEGRADED} at registration time.
     *
     * @param status the health status that triggered this fallback
     * @param app    the CafeAI application being configured
     */
    void onUnavailable(HealthStatus status, CafeAI app);

    // -- Built-in fallback strategies ------------------------------------------

    /**
     * Logs a warning and continues. The service may become reachable later --
     * operations that need it will fail at call time, not at startup.
     *
     * <p>This is the default for all connections unless overridden.
     */
    static Fallback warnAndContinue() {
        Logger log = LoggerFactory.getLogger(Fallback.class);
        return (status, app) ->
            log.warn("WARN: Service unavailable at startup: {} -- continuing without it. " +
                "Operations requiring this service will fail until it becomes reachable.",
                status);
    }

    /**
     * Throws {@link ServiceUnavailableException} immediately, aborting startup.
     *
     * <p>Use for services that are non-negotiable for the application to function.
     */
    static Fallback failFast() {
        return (status, app) -> {
            throw new ServiceUnavailableException(
                "Required service is unavailable: " + status +
                "\nTo allow startup without this service, use .onUnavailable(Fallback.warnAndContinue())");
        };
    }

    /**
     * Falls back to an alternative CafeAI provider or capability.
     *
     * <p>The fallback object is passed to the appropriate {@code app.ai()},
     * {@code app.memory()}, etc. method based on its type. Use this to
     * seamlessly switch between local and cloud providers:
     *
     * <pre>{@code
     *   app.connect(Ollama.at("http://ollama:11434").model("llama3")
     *       .onUnavailable(Fallback.use(OpenAI.gpt4o())));
     *   // Ollama locally in dev, OpenAI in prod if Ollama isn't available
     * }</pre>
     *
     * @param alternative an {@code AiProvider}, {@code MemoryStrategy}, or other
     *                    CafeAI-compatible object to register as the fallback
     */
    static Fallback use(Object alternative) {
        Logger log = LoggerFactory.getLogger(Fallback.class);
        return (status, app) -> {
            log.warn("WARN: Service unavailable: {} -- activating fallback: {}",
                status.service(), alternative.getClass().getSimpleName());
            registerFallback(alternative, app);
        };
    }

    /**
     * Registers an alternative {@link Connection} when the primary is unavailable.
     *
     * <pre>{@code
     *   app.connect(PgVector.at("jdbc:postgresql://pgvector/cafeai")
     *       .onUnavailable(Fallback.connectInstead(VectorStore.inMemory())));
     * }</pre>
     */
    static Fallback connectInstead(Object connection) {
        Logger log = LoggerFactory.getLogger(Fallback.class);
        return (status, app) -> {
            log.warn("WARN: Service unavailable: {} -- connecting to fallback: {}",
                status.service(), connection.getClass().getSimpleName());
            if (connection instanceof Connection conn) {
                conn.register(app);
            } else {
                registerFallback(connection, app);
            }
        };
    }

    /**
     * Silently does nothing. The capability simply won't be available.
     * Use when the service is truly optional and absence is expected.
     */
    static Fallback ignore() {
        return (status, app) -> {};
    }

    // -- Internal --------------------------------------------------------------

    private static void registerFallback(Object alternative, CafeAI app) {
        // Route based on type -- mirrors what Connection.register() does
        if (alternative instanceof io.cafeai.core.ai.AiProvider provider) {
            app.ai(provider);
        } else if (alternative instanceof io.cafeai.core.memory.MemoryStrategy strategy) {
            app.memory(strategy);
        } else {
            // For vectordb, embed, etc. -- use the Object-based registration
            try {
                app.vectordb(alternative);
            } catch (Exception ignored) {
                try { app.embed(alternative); }
                catch (Exception e) {
                    throw new IllegalArgumentException(
                        "Cannot register fallback of type " +
                        alternative.getClass().getName() +
                        " -- not a recognized CafeAI capability type", e);
                }
            }
        }
    }

    /** Thrown by {@link #failFast()} when a required service is unreachable. */
    class ServiceUnavailableException extends RuntimeException {
        public ServiceUnavailableException(String message) { super(message); }
    }
}
