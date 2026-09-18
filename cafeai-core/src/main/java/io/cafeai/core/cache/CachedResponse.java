package io.cafeai.core.cache;

import java.time.Instant;

/**
 * An answer served from a {@link SemanticCache}.
 *
 * @param id       identifies the entry, so a caller that decides it is unfit can
 *                 {@link SemanticCache#evict(String) evict} it
 * @param text     the cached response text
 * @param storedAt when it was admitted to the cache
 */
public record CachedResponse(String id, String text, Instant storedAt) {}
