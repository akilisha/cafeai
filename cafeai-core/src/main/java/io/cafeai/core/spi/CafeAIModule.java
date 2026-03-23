package io.cafeai.core.spi;

/**
 * Implemented by CafeAI modules to self-register their capabilities.
 *
 * <p>When a {@code cafeai-*} module JAR is added to the classpath, it
 * registers its capabilities automatically via {@link java.util.ServiceLoader}
 * — no configuration required. Adding the JAR is the configuration.
 *
 * <p>Modules declare themselves in:
 * {@code META-INF/services/io.cafeai.core.spi.CafeAIModule}
 *
 * <p>Example — a hypothetical {@code cafeai-pinecone} module:
 * <pre>{@code
 *   public class PineconeModule implements CafeAIModule {
 *       @Override public String name()    { return "cafeai-pinecone"; }
 *       @Override public String version() { return "1.0.0"; }
 *
 *       @Override
 *       public void register(CafeAIRegistry registry) {
 *           registry.registerVectorStore("pinecone", PineconeVectorStore::new);
 *       }
 *   }
 * }</pre>
 */
public interface CafeAIModule {

    /** Human-readable module name. Logged at INFO on startup. */
    String name();

    /** Module version string. Logged at INFO on startup. */
    String version();

    /**
     * Registers this module's capabilities into the CafeAI registry.
     * Called once at application startup, before any configurers run.
     *
     * @param registry the registry to register capabilities into
     */
    void register(CafeAIRegistry registry);
}
