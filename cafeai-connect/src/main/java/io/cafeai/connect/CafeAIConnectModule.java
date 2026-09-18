package io.cafeai.connect;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-connect} at startup.
 *
 * <p>Signals that out-of-process service connectivity is available
 * via {@code app.connect()}. Connections are registered per instance via
 * {@code app.connect()}; each {@code Connection} implementation is
 * self-contained and carries its own probe and register logic, so there is
 * nothing to wire here.
 */
public final class CafeAIConnectModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-connect"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }
}
