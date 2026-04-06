package io.cafeai.rag;

/**
 * Factory for Chroma-backed {@link VectorStore} instances.
 *
 * <p>Chroma is a lightweight, open-source vector database ideal for local
 * development and single-node deployments. Documents persist across
 * application restarts — unlike {@link VectorStore#inMemory()}.
 *
 * <pre>{@code
 *   // Local Chroma on default port
 *   app.vectordb(Chroma.local());
 *
 *   // Remote or custom URL
 *   app.vectordb(Chroma.connect("http://chroma.internal:8000"));
 *
 *   // Custom collection name (recommended for production)
 *   app.vectordb(Chroma.connect("http://localhost:8000", "acme-claims"));
 * }</pre>
 *
 * <p><strong>Prerequisites:</strong> Chroma must be running before the
 * application starts. Start with Docker:
 *
 * <pre>
 *   docker run -p 8000:8000 chromadb/chroma:0.5.23
 * </pre>
 *
 * <p><strong>Version compatibility:</strong> LangChain4j's Chroma integration
 * is compatible with Chroma 0.5.x. Chroma 0.6+ changed its API and is not
 * yet supported — pin to {@code chromadb/chroma:0.5.23} until updated.
 *
 * <p><strong>Compared to {@code VectorStore.inMemory()}:</strong>
 * <ul>
 *   <li>Documents survive application restarts</li>
 *   <li>Knowledge base can be populated once and reused</li>
 *   <li>Collections are visible and queryable via the Chroma REST API</li>
 *   <li>Requires a running Chroma instance (Docker recommended)</li>
 * </ul>
 */
public final class Chroma {

    /** Default collection name used when none is specified. */
    public static final String DEFAULT_COLLECTION = "cafeai";

    /** Default Chroma base URL. */
    public static final String DEFAULT_URL = "http://localhost:8000";

    private Chroma() {}

    /**
     * Connects to Chroma running locally on the default port (8000),
     * using the default collection name {@code "cafeai"}.
     *
     * <p>Equivalent to {@code Chroma.connect("http://localhost:8000")}.
     */
    public static VectorStore local() {
        return connect(DEFAULT_URL, DEFAULT_COLLECTION);
    }

    /**
     * Connects to Chroma at the given base URL, using the default
     * collection name {@code "cafeai"}.
     *
     * @param baseUrl Chroma base URL, e.g. {@code "http://localhost:8000"}
     */
    public static VectorStore connect(String baseUrl) {
        return connect(baseUrl, DEFAULT_COLLECTION);
    }

    /**
     * Connects to Chroma at the given base URL with a specific collection name.
     *
     * <p>Use a stable, application-specific collection name so documents
     * persist correctly across restarts. The collection is created if it
     * does not already exist.
     *
     * @param baseUrl        Chroma base URL, e.g. {@code "http://localhost:8000"}
     * @param collectionName the Chroma collection to use, e.g. {@code "acme-claims"}
     */
    public static VectorStore connect(String baseUrl, String collectionName) {
        return new ChromaVectorStoreAdapter(baseUrl, collectionName);
    }
}
