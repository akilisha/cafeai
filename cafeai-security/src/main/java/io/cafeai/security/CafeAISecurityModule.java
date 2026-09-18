package io.cafeai.security;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-security} at startup.
 */
public final class CafeAISecurityModule implements CafeAIModule {

    @Override public String name()    { return "cafeai-security"; }
    @Override public String version() { return CafeAIModule.versionOf(getClass()); }
}
