package io.cafeai.core.cache;

import io.cafeai.core.rag.EmbeddingProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The built-in, per-process {@link SemanticCache}. See that interface for the poisoning defences
 * this implements: namespace isolation, a three-way match test, a lifetime, and a size bound.
 *
 * <p>A hit needs <em>all</em> of: cosine similarity {@code >= threshold}; word overlap (Jaccard)
 * {@code >= minTokenOverlap}; and prompt lengths within {@code maxLengthRatio} of each other. The
 * last two are what stop a near-neighbour crafted by appending text to a popular prompt.
 *
 * <p>Lookups scan the namespace linearly under a lock — fine for thousands of entries. It is
 * least-recently-used when full.
 */
public final class InMemorySemanticCache implements SemanticCache {

    private record Entry(String id, String namespace, String prompt, Set<String> words,
                         float[] vector, String response, Instant storedAt) {}

    private final EmbeddingProvider embedder;
    private final double threshold;
    private final double minTokenOverlap;
    private final double maxLengthRatio;
    private final Duration ttl;
    private final int maxEntries;
    private final int maxResponseChars;
    private final Clock clock;

    private final Object lock = new Object();
    /** Access-ordered, so the eldest entry is the least recently used. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    /** A lookup is nearly always followed by a store of the same prompt; embed it once. */
    private final Map<String, float[]> recentEmbeddings = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) { return size() > 64; }
    };

    private InMemorySemanticCache(Builder b) {
        this.embedder         = b.embedder;
        this.threshold        = b.threshold;
        this.minTokenOverlap  = b.minTokenOverlap;
        this.maxLengthRatio   = b.maxLengthRatio;
        this.ttl              = b.ttl;
        this.maxEntries       = b.maxEntries;
        this.maxResponseChars = b.maxResponseChars;
        this.clock            = b.clock;
    }

    public static Builder builder(EmbeddingProvider embedder) {
        return new Builder(Objects.requireNonNull(embedder, "EmbeddingProvider must not be null"));
    }

    // ── SemanticCache ─────────────────────────────────────────────────────────

    @Override
    public Optional<CachedResponse> lookup(String namespace, String prompt) {
        if (namespace == null || prompt == null || prompt.isBlank()) return Optional.empty();
        float[] query = embed(prompt);              // outside the lock: may be a network call
        Set<String> words = words(prompt);
        Instant now = clock.instant();

        synchronized (lock) {
            Entry best = null;
            double bestSimilarity = -1;
            for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (expired(e, now)) { it.remove(); continue; }
                if (!e.namespace().equals(namespace)) continue;
                if (lengthRatio(prompt, e.prompt()) > maxLengthRatio) continue;
                double similarity = dot(query, e.vector());
                if (similarity < threshold || similarity <= bestSimilarity) continue;
                if (jaccard(words, e.words()) < minTokenOverlap) continue;
                best = e;
                bestSimilarity = similarity;
            }
            if (best == null) return Optional.empty();
            entries.get(best.id());                 // touch: most recently used
            return Optional.of(new CachedResponse(best.id(), best.response(), best.storedAt()));
        }
    }

    @Override
    public boolean store(String namespace, String prompt, String response) {
        if (namespace == null || prompt == null || prompt.isBlank()) return false;
        if (response == null || response.isBlank() || response.length() > maxResponseChars) return false;
        float[] vector = embed(prompt);
        Entry entry = new Entry(UUID.randomUUID().toString(), namespace, prompt.strip(),
                words(prompt), vector, response, clock.instant());

        synchronized (lock) {
            // The same prompt in the same namespace replaces the older answer.
            entries.values().removeIf(e ->
                e.namespace().equals(namespace) && e.prompt().equalsIgnoreCase(entry.prompt()));
            entries.put(entry.id(), entry);
            while (entries.size() > maxEntries) {
                Iterator<Entry> eldest = entries.values().iterator();
                eldest.next();
                eldest.remove();
            }
        }
        return true;
    }

    @Override
    public void evict(String entryId) {
        if (entryId == null) return;
        synchronized (lock) { entries.remove(entryId); }
    }

    @Override
    public void clear() {
        synchronized (lock) { entries.clear(); }
    }

    @Override
    public long size() {
        Instant now = clock.instant();
        synchronized (lock) {
            entries.values().removeIf(e -> expired(e, now));
            return entries.size();
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private boolean expired(Entry e, Instant now) {
        return !e.storedAt().plus(ttl).isAfter(now);
    }

    private float[] embed(String text) {
        String key = text.strip();
        synchronized (recentEmbeddings) {
            float[] memo = recentEmbeddings.get(key);
            if (memo != null) return memo;
        }
        float[] raw = embedder.embed(key);
        double norm = 0;
        for (float v : raw) norm += (double) v * v;
        norm = Math.sqrt(norm);
        float[] unit = new float[raw.length];
        if (norm > 0) for (int i = 0; i < raw.length; i++) unit[i] = (float) (raw[i] / norm);
        synchronized (recentEmbeddings) { recentEmbeddings.put(key, unit); }
        return unit;
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) return -1;         // a different embedding model: never a match
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += (double) a[i] * b[i];
        return sum;
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) words.add(w);
        }
        return words;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int intersection = 0;
        for (String w : a) if (b.contains(w)) intersection++;
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 1.0 : (double) intersection / union;
    }

    private static double lengthRatio(String a, String b) {
        int x = Math.max(1, a.strip().length());
        int y = Math.max(1, b.strip().length());
        return (double) Math.max(x, y) / Math.min(x, y);
    }

    // ── builder ───────────────────────────────────────────────────────────────

    /** Defaults are deliberately strict: a missed hit costs a model call; a wrong hit costs trust. */
    public static final class Builder {
        private final EmbeddingProvider embedder;
        private double threshold       = 0.95;
        private double minTokenOverlap = 0.80;
        private double maxLengthRatio  = 1.25;
        private Duration ttl           = Duration.ofHours(1);
        private int maxEntries         = 1_000;
        private int maxResponseChars   = 8_000;
        private Clock clock            = Clock.systemUTC();

        private Builder(EmbeddingProvider embedder) { this.embedder = embedder; }

        /** Minimum cosine similarity between prompt embeddings, in (0, 1]. Default 0.95. */
        public Builder threshold(double similarity) {
            if (similarity <= 0 || similarity > 1) throw new IllegalArgumentException("threshold must be in (0, 1]");
            this.threshold = similarity;
            return this;
        }

        /** Minimum word overlap (Jaccard) between prompts, in [0, 1]. Default 0.80. */
        public Builder minTokenOverlap(double overlap) {
            if (overlap < 0 || overlap > 1) throw new IllegalArgumentException("minTokenOverlap must be in [0, 1]");
            this.minTokenOverlap = overlap;
            return this;
        }

        /** Largest allowed ratio between the longer and shorter prompt, at least 1. Default 1.25. */
        public Builder maxLengthRatio(double ratio) {
            if (ratio < 1) throw new IllegalArgumentException("maxLengthRatio must be >= 1");
            this.maxLengthRatio = ratio;
            return this;
        }

        /** How long an entry may be served. Default one hour. */
        public Builder ttl(Duration ttl) {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("ttl must be positive");
            this.ttl = ttl;
            return this;
        }

        /** Most entries kept; the least recently used is dropped. Default 1000. */
        public Builder maxEntries(int max) {
            if (max < 1) throw new IllegalArgumentException("maxEntries must be >= 1");
            this.maxEntries = max;
            return this;
        }

        /** Longest response admitted, in characters. Default 8000. */
        public Builder maxResponseChars(int max) {
            if (max < 1) throw new IllegalArgumentException("maxResponseChars must be >= 1");
            this.maxResponseChars = max;
            return this;
        }

        /** For tests: the clock TTL is measured against. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        public InMemorySemanticCache build() { return new InMemorySemanticCache(this); }
    }
}
