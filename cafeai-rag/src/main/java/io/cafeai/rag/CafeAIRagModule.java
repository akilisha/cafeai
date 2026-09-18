package io.cafeai.rag;

import io.cafeai.core.spi.CafeAIModule;

/**
 * Announces {@code cafeai-rag} at startup.
 *
 * <p>The embedding providers and vector stores are not registered here; they are
 * reached through {@code RagProvider}. The local ONNX embedding model and the
 * in-memory vector store work immediately, while cloud and database-backed
 * variants need connection configuration.
 */
public final class CafeAIRagModule implements CafeAIModule {

    @Override
    public String name()    { return "cafeai-rag"; }

    @Override
    public String version() { return CafeAIModule.versionOf(getClass()); }
}
