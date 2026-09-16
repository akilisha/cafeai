package io.cafeai.core.spi;

/**
 * Implemented by CafeAI modules to self-register their capabilities.
 *
 * <p>When a {@code cafeai-*} module JAR is added to the classpath, it
 * registers its capabilities automatically via {@link java.util.ServiceLoader}
 * -- no configuration required. Adding the JAR is the configuration.
 *
 * <p>Modules declare themselves in:
 * {@code META-INF/services/io.cafeai.core.spi.CafeAIModule}
 *
 * <p>Example -- a hypothetical {@code cafeai-pinecone} module:
 * <pre>{@code
 *   public class PineconeModule implements CafeAIModule {
 *       @Override public String name()    { return "cafeai-pinecone"; }
 *       @Override public String version() { return CafeAIModule.versionOf(getClass()); }
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

    /**
     * Reads a module's version from its JAR manifest ({@code Implementation-Version},
     * stamped by the build from {@code project.version} — see the root
     * {@code build.gradle}) rather than a hardcoded literal, which drifts the
     * moment the module is released again. Implementations should call this
     * with their own class from {@link #version()}:
     *
     * <pre>{@code
     *   @Override public String version() { return CafeAIModule.versionOf(getClass()); }
     * }</pre>
     *
     * <p>Returns {@code "dev"} when run from an exploded classpath (IDE run,
     * tests) rather than a packaged JAR, where no manifest is present.
     */
    static String versionOf(Class<?> moduleClass) {
        String v = moduleClass.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
