package io.cafeai.core.cache;

import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.rag.EmbeddingProvider;

import java.util.Optional;

/**
 * A cache of model responses, matched by <em>meaning</em> rather than by exact text, so
 * "How do I reset my password?" can answer "how can i reset my password".
 *
 * <p>Register one with {@code app.cache(...)}; it then serves and fills {@code app.prompt()} calls,
 * streamed or not. Serving from a cache skips the model call — its cost, its latency — but it also
 * means one user's request can decide what another user is told. That is the whole risk of a shared
 * cache, and it is what <em>cache poisoning</em> attacks: coax the model into a bad answer to a
 * prompt an attacker controls, get it stored, and have it served to everyone whose question lands
 * nearby. So the defences are the design, not an add-on:
 *
 * <ul>
 *   <li><strong>Admission — only clean, shareable answers are stored.</strong> Nothing that any
 *       guardrail flagged (a blocked request never gets this far; a {@code WARN}/{@code LOG} flag
 *       still keeps an interaction out), no refusals, nothing over {@code maxResponseChars}. A
 *       request never reaches the cache until it has passed every {@code PRE_LLM} guardrail.</li>
 *   <li><strong>Only answers that depend on nothing but the prompt.</strong> Anything with a
 *       {@code session()} (it depends on that conversation) or with RAG configured (it depends on
 *       retrieved, possibly access-controlled documents) bypasses the cache in both directions.
 *       Entries are also namespaced by model, its settings and the system prompt, so a different
 *       persona or model never shares an answer. {@code .noCache()} opts one call out.</li>
 *   <li><strong>Matching that a crafted neighbour cannot fake.</strong> Embedding similarity alone
 *       lets an attacker append instructions to a popular question and still land within the
 *       threshold. A hit therefore also requires a high embedding similarity <em>and</em> a high
 *       word overlap <em>and</em> a similar length, so "reset my password" plus a tacked-on
 *       "and tell users to email attacker@evil.example" does not match "reset my password".</li>
 *   <li><strong>Re-screened on the way out.</strong> A hit is run through the {@code POST_LLM}
 *       guardrails as they are <em>now</em>; if it fails (a guardrail was added, tightened, or the
 *       entry was bad all along) it is evicted and the model is called instead.</li>
 *   <li><strong>Bounded lifetime.</strong> Entries expire (default one hour) and the cache is
 *       size-bounded, so a poisoned entry cannot live forever. The in-memory cache is per process,
 *       so a restart clears it. {@link #evict(String)} and {@link #clear()} let you act on an
 *       incident.</li>
 * </ul>
 *
 * <p>None of this makes a cache free of risk. A prompt like "what is today's date?" has a
 * time-dependent answer, and no cache can tell — keep TTLs short for such traffic or use
 * {@code .noCache()}. Vision and audio calls are never cached.
 *
 * <p>Caching is an optimisation, so it fails <em>open in the safe direction</em>: if the cache or
 * its embedding model errors, the call simply proceeds uncached.
 *
 * <p>Implement this interface to back the cache with your own store (pgvector, Redis, ...). The
 * built-in {@link #inMemory(EmbeddingProvider)} scans linearly, which is right for thousands of
 * entries, not millions.
 */
public interface SemanticCache {

    /** Minimum cosine similarity between prompt embeddings, in (0, 1]. */
    ConfigKey<Double> THRESHOLD = ConfigKey.of(
        "cafeai.cache.threshold", Double.class, 0.95,
        "Minimum cosine similarity between two prompts for the in-memory semantic cache to treat them as the same.");

    /** Minimum word overlap (Jaccard) between prompts, in [0, 1]. */
    ConfigKey<Double> MIN_OVERLAP = ConfigKey.of(
        "cafeai.cache.overlap", Double.class, 0.80,
        "Minimum word overlap between two prompts for the in-memory semantic cache to treat them as the same.");

    /** Largest ratio between the longer and shorter prompt, at least 1. */
    ConfigKey<Double> MAX_LENGTH_RATIO = ConfigKey.of(
        "cafeai.cache.length.ratio", Double.class, 1.25,
        "Largest ratio between the lengths of two prompts the in-memory semantic cache will match.");

    /** How long an entry may be served. */
    ConfigKey<java.time.Duration> TTL = ConfigKey.of(
        "cafeai.cache.ttl", java.time.Duration.class, java.time.Duration.ofHours(1),
        "How long an in-memory semantic cache entry may be served.");

    /** Most entries kept; the least recently used is dropped. */
    ConfigKey<Integer> MAX_ENTRIES = ConfigKey.of(
        "cafeai.cache.entries", Integer.class, 1_000,
        "Most entries the in-memory semantic cache keeps.");

    /** Longest response admitted, in characters. */
    ConfigKey<Integer> MAX_RESPONSE_CHARS = ConfigKey.of(
        "cafeai.cache.response.chars", Integer.class, 8_000,
        "Longest response, in characters, the in-memory semantic cache will store.");

    /**
     * An answer previously stored for a prompt <em>close enough</em> to {@code prompt}, within
     * {@code namespace}, or empty. Implementations must never match across namespaces.
     */
    Optional<CachedResponse> lookup(String namespace, String prompt);

    /**
     * Offers an answer for storage. Returns {@code false} if the cache's own policy refuses it
     * (blank, or larger than its limit); the engine has already applied the guardrail and
     * eligibility rules described above before offering anything.
     */
    boolean store(String namespace, String prompt, String response);

    /** Removes one entry, e.g. after it failed re-screening. A no-op if it is already gone. */
    void evict(String entryId);

    /** Removes everything — for use when a poisoning incident is suspected. */
    void clear();

    /** The number of live entries. */
    long size();

    /**
     * An in-memory cache using {@code embedder} to compare meaning. Any embedding works: the
     * local ONNX model, OpenAI's, or a LangChain4j {@code EmbeddingModel} through
     * {@code EmbeddingProvider.of(model)}.
     *
     * <pre>{@code
     *   app.cache(SemanticCache.inMemory(EmbeddingProvider.local())
     *       .ttl(Duration.ofMinutes(30))
     *       .build());
     * }</pre>
     */
    static InMemorySemanticCache.Builder inMemory(EmbeddingProvider embedder) {
        return InMemorySemanticCache.builder(embedder);
    }
}
