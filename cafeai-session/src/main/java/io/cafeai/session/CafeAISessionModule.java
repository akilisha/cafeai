package io.cafeai.session;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-session} at startup.
 *
 * <p>Discovered via {@link java.util.ServiceLoader} when the
 * {@code cafeai-session} JAR is on the classpath. {@link io.cafeai.core.session.SessionStore#sqlite()}
 * is not registered here; it is reached through {@code SessionStoreProvider},
 * and still needs explicit registration --
 * {@code app.filter(Middleware.session(SessionStore.sqlite()))} -- to activate.
 */
public final class CafeAISessionModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-session"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }
}
