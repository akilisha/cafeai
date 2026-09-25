package io.cafeai.core.spi;

import io.cafeai.core.session.SessionStore;

import java.nio.file.Path;

/**
 * SPI for the {@code cafeai-session} module to provide the real, SQLite-backed
 * {@link SessionStore}.
 *
 * <p>Mirrors the pattern of {@link MemoryStrategyProvider} -- adding the
 * {@code cafeai-session} JAR to the classpath activates {@link SessionStore#sqlite()}.
 * No code changes required.
 */
public interface SessionStoreProvider {

    SessionStore sqlite();

    SessionStore sqlite(Path dbFile);
}
