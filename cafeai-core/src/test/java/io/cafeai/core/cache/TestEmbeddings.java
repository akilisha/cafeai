package io.cafeai.core.cache;

import io.cafeai.core.rag.EmbeddingProvider;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic embedders for tests: no model, no network, controllable failure modes. */
final class TestEmbeddings {

    private TestEmbeddings() {}

    /** A hashed bag of words over the whole text — a crude but honest stand-in for meaning. */
    static EmbeddingProvider bagOfWords() { return new Hashing(Integer.MAX_VALUE); }

    /**
     * Embeds only the first {@code words} words. This models what a real embedding model does when
     * a prompt is dominated by its leading topic: a victim's question with instructions appended
     * embeds <em>identically</em> to the question itself, so similarity alone cannot tell them apart.
     */
    static Hashing leadingWords(int words) { return new Hashing(words); }

    static final class Hashing implements EmbeddingProvider {
        static final int DIMENSIONS = 128;
        final AtomicInteger calls = new AtomicInteger();
        private final int limit;
        volatile RuntimeException failure;

        Hashing(int limit) { this.limit = limit; }

        @Override public float[] embed(String text) {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            float[] v = new float[DIMENSIONS];
            String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
            int used = 0;
            for (String w : words) {
                if (w.isEmpty()) continue;
                if (used++ >= limit) break;
                v[Math.floorMod(w.hashCode(), DIMENSIONS)] += 1f;
            }
            return v;
        }

        @Override public int dimensions() { return DIMENSIONS; }
        @Override public String modelId() { return "test-hashing-" + limit; }
    }
}
