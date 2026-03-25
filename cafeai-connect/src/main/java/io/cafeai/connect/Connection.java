package io.cafeai.connect;

import io.cafeai.core.CafeAI;

/**
 * An out-of-process service that CafeAI connects to.
 *
 * <p>This is the central abstraction of {@code cafeai-connect}. Every external
 * service — a Redis instance, a pgvector database, an Ollama server, an MCP
 * endpoint, or any future service — is a {@code Connection}.
 *
 * <p>A {@code Connection} is not a module. It does not run inside the JVM.
 * It has its own lifecycle, its own scale, its own deployment. CafeAI reaches
 * out to it over a network boundary.
 *
 * <p>The contract has three parts:
 * <ol>
 *   <li>{@link #probe()} — can we reach it right now?</li>
 *   <li>{@link #register(CafeAI)} — wire its capability into the application</li>
 *   <li>{@link #onUnavailable(Fallback)} — what to do if probe fails</li>
 * </ol>
 *
 * <p>Register via {@code app.connect()}:
 *
 * <pre>{@code
 *   app.connect(Redis.at("redis:6379"));
 *   app.connect(Ollama.at("http://ollama:11434").model("llama3"));
 *   app.connect(PgVector.at("jdbc:postgresql://pgvector/cafeai"));
 *   app.connect(McpEndpoint.at("http://github-mcp:3000"));
 *
 *   // With fallback policy
 *   app.connect(Ollama.at("http://ollama:11434")
 *       .onUnavailable(Fallback.use(OpenAI.gpt4o())));
 *
 *   // Environment-driven — reads CAFEAI_* variables
 *   app.connect(Connect.fromEnv());
 * }</pre>
 *
 * <p><strong>Custom connections:</strong> implement this interface to connect
 * CafeAI to any service that doesn't have a built-in connector. The same
 * probe/register/fallback model applies regardless of the protocol.
 *
 * <pre>{@code
 *   public class MyVectorDb implements Connection {
 *       public String name() { return "my-vector-db"; }
 *       public ServiceType type() { return ServiceType.VECTOR_DB; }
 *
 *       public HealthStatus probe() {
 *           // ping the service; return reachable/unreachable/degraded
 *       }
 *
 *       public void register(CafeAI app) {
 *           app.vectordb(new MyVectorStoreAdapter(this));
 *       }
 *   }
 *
 *   app.connect(new MyVectorDb());
 * }</pre>
 */
public interface Connection {

    /** Human-readable name used in logs and health check output. */
    String name();

    /** The type of capability this connection provides to CafeAI. */
    ServiceType type();

    /**
     * Probes the service to determine if it is currently reachable.
     *
     * <p>Called once at registration time (during {@code app.connect()}) and
     * periodically by {@link Connect#healthCheck(CafeAI)}.
     *
     * <p>Implementations must not throw — catch all exceptions and return
     * an appropriate {@link HealthStatus}.
     *
     * @return the current reachability state of the service
     */
    HealthStatus probe();

    /**
     * Wires this connection's capability into the CafeAI application.
     *
     * <p>Called by {@code app.connect()} after a successful probe.
     * A Redis connection calls {@code app.memory(MemoryStrategy.redis(...))}.
     * An Ollama connection calls {@code app.ai(Ollama.at(...))}.
     * The developer never needs to know the details.
     *
     * @param app the CafeAI application to configure
     */
    void register(CafeAI app);

    /**
     * Returns this connection with a custom fallback policy.
     *
     * <p>Default fallback is {@link Fallback#warnAndContinue()} — log a warning
     * if the service is unreachable at startup, continue without it.
     *
     * @param fallback what to do if {@link #probe()} shows the service is unavailable
     * @return a new connection with the given fallback policy
     */
    default Connection onUnavailable(Fallback fallback) {
        Connection delegate = this;
        return new Connection() {
            @Override public String name()           { return delegate.name(); }
            @Override public ServiceType type()      { return delegate.type(); }
            @Override public HealthStatus probe()    { return delegate.probe(); }
            @Override public void register(CafeAI a) { delegate.register(a); }
            @Override public Fallback fallback()     { return fallback; }
        };
    }

    /**
     * The fallback policy for this connection. Default: {@link Fallback#warnAndContinue()}.
     * Override via {@link #onUnavailable(Fallback)}.
     */
    default Fallback fallback() {
        return Fallback.warnAndContinue();
    }

    /**
     * The type of capability an out-of-process service provides.
     * Used for logging, health checks, and routing registration calls.
     */
    enum ServiceType {
        /** A language model (Ollama, OpenAI-compatible, etc.) */
        LLM,
        /** A distributed memory / session store (Redis, Memcached) */
        MEMORY,
        /** A vector database (pgvector, Chroma, Pinecone, Weaviate, etc.) */
        VECTOR_DB,
        /** An MCP protocol server */
        MCP,
        /** An embedding model service */
        EMBEDDING,
        /**
         * Any other service. Use for custom connections.
         * {@link #register(CafeAI)} is called but type-based routing does not apply.
         */
        CUSTOM
    }
}
