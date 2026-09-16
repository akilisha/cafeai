package io.cafeai.core.rag;

/**
 * Thrown when a RAG capability requiring {@code cafeai-rag} is requested but
 * that module is not on the classpath — mirrors
 * {@link io.cafeai.core.memory.MemoryStrategy.MemoryModuleNotFoundException}.
 */
public final class RagModuleNotFoundException extends RuntimeException {
    public RagModuleNotFoundException(String message) {
        super(message);
    }
}
