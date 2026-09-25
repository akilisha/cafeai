package io.cafeai.core.session;

import io.cafeai.core.spi.SessionStoreProvider;

import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pluggable backing store for HTTP sessions.
 *
 * <p>See {@link Session}'s Javadoc for the disambiguation from
 * {@link io.cafeai.core.memory.MemoryStrategy}'s AI-conversation "session".
 *
 * <pre>{@code
 *   app.filter(Middleware.session(SessionStore.inMemory()));  // dev/test only
 *   app.filter(Middleware.session(SessionStore.sqlite()));    // real default, requires cafeai-session
 * }</pre>
 *
 * <p>Unlike {@code MemoryStrategy}, there is no bare zero-arg default on
 * {@code Middleware.session(...)} -- every store is explicit at the call site,
 * so the persistence guarantee is visible rather than silently depending on
 * what happens to be on the classpath.
 *
 * <p>{@link #sqlite()} is single-instance only. For multi-instance/multi-pod
 * deployments, supply your own {@code SessionStore} backed by a shared store
 * (Redis or otherwise) -- see {@code RedisSessionExample} in {@code cafeai-examples}.
 * CafeAI does not ship a distributed session store.
 */
public interface SessionStore {

    /** Generates a new opaque session ID and returns a new, unsaved {@link Session}. */
    Session create();

    /** Loads a session by ID. Returns {@code null} if not found. Thread-safe. */
    Session load(String sessionId);

    /** Persists (insert or update) a session's attributes. Thread-safe. */
    void save(Session session);

    /** Deletes a session. No-op if absent. */
    void destroy(String sessionId);

    /** {@code true} if a session with this ID currently exists. */
    boolean exists(String sessionId);

    // -- Dev/test rung: zero deps, zero config, lost on restart ---------------

    /**
     * In-JVM {@code ConcurrentHashMap}. Zero dependencies. Zero configuration.
     * Sessions do not survive restarts. Single-node only.
     *
     * <p>Appropriate for: development, testing. Not for real deployments --
     * a session store that forgets everything on restart defeats the point
     * of having one. Use {@link #sqlite()} for real applications.
     */
    static SessionStore inMemory() {
        return new InMemorySessionStore();
    }

    // -- Real default rung: requires cafeai-session ----------------------------

    /**
     * SQLite-backed session store at the default location
     * ({@code ${java.io.tmpdir}/cafeai/sessions.db}). Real WAL, real
     * concurrent connections, real file durability. Single-instance only.
     *
     * <p>Requires {@code com.akilisha.oss:cafeai-session} on the classpath.
     *
     * @throws SessionModuleNotFoundException if {@code cafeai-session} is absent
     */
    static SessionStore sqlite() {
        return loadProvider().sqlite();
    }

    /**
     * SQLite-backed session store at a custom file path.
     *
     * @throws SessionModuleNotFoundException if {@code cafeai-session} is absent
     */
    static SessionStore sqlite(Path dbFile) {
        return loadProvider().sqlite(dbFile);
    }

    // -- ServiceLoader discovery ------------------------------------------------

    private static SessionStoreProvider loadProvider() {
        return ServiceLoader.load(SessionStoreProvider.class)
            .findFirst()
            .orElseThrow(() -> new SessionModuleNotFoundException(
                "SessionStore.sqlite() requires the cafeai-session module. " +
                "Add the following dependency:\n\n" +
                "  Gradle: implementation 'com.akilisha.oss:cafeai-session'\n" +
                "  Maven:  <artifactId>cafeai-session</artifactId>\n\n" +
                "For development, use SessionStore.inMemory() (zero dependencies)."));
    }

    /**
     * Thrown when {@link #sqlite()}/{@link #sqlite(Path)} is requested but
     * {@code cafeai-session} is not on the classpath.
     */
    class SessionModuleNotFoundException extends RuntimeException {
        public SessionModuleNotFoundException(String message) {
            super(message);
        }
    }

    // -- Dev/test rung implementation --------------------------------------------

    final class InMemorySessionStore implements SessionStore {
        private final ConcurrentHashMap<String, Session> store = new ConcurrentHashMap<>();

        @Override
        public Session create() {
            return new Session(UUID.randomUUID().toString());
        }

        @Override public Session load(String id)   { return store.get(id); }
        @Override public void destroy(String id)   { store.remove(id); }
        @Override public boolean exists(String id) { return store.containsKey(id); }

        @Override
        public void save(Session session) {
            store.put(session.id(), session);
        }
    }
}
