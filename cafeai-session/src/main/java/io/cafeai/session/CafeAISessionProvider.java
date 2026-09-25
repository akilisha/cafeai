package io.cafeai.session;

import io.cafeai.core.session.SessionStore;
import io.cafeai.core.spi.SessionStoreProvider;

import java.nio.file.Path;

/**
 * ServiceLoader registration for {@code cafeai-session}.
 *
 * <p>Adding {@code com.akilisha.oss:cafeai-session} to the classpath activates this
 * provider, enabling {@link SessionStore#sqlite()} / {@link SessionStore#sqlite(Path)}
 * without any code changes.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.SessionStoreProvider}
 */
public final class CafeAISessionProvider implements SessionStoreProvider {

    @Override
    public SessionStore sqlite() {
        return new SqliteSessionStore();
    }

    @Override
    public SessionStore sqlite(Path dbFile) {
        return new SqliteSessionStore(dbFile);
    }
}
