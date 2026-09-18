package io.cafeai.core.spi;

/**
 * Implemented by CafeAI modules so their presence is announced at startup.
 *
 * <p>When a {@code cafeai-*} module JAR is on the classpath, CafeAI discovers it
 * via {@link java.util.ServiceLoader} and logs its name and version
 * ({@code CafeAI module loaded: cafeai-rag v0.4.0}). Adding the JAR is the
 * configuration.
 *
 * <p><strong>Informational, not load-bearing.</strong> A module's capabilities
 * are wired through the provider SPIs ({@code GuardRailProvider},
 * {@code RagProvider}, ...), which are discovered independently — this interface
 * does not register anything. A module works whether or not it implements it;
 * implement it so the module shows up in the startup log.
 *
 * <p>Modules declare themselves in:
 * {@code META-INF/services/io.cafeai.core.spi.CafeAIModule}
 *
 * <p>Example -- a hypothetical {@code cafeai-pinecone} module:
 * <pre>{@code
 *   public class PineconeModule implements CafeAIModule {
 *       @Override public String name()    { return "cafeai-pinecone"; }
 *       @Override public String version() { return CafeAIModule.versionOf(getClass()); }
 *   }
 * }</pre>
 */
public interface CafeAIModule {

    /** Human-readable module name. Logged at INFO on startup. */
    String name();

    /** Module version string. Logged at INFO on startup. */
    String version();

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
