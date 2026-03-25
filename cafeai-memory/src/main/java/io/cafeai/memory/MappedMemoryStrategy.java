package io.cafeai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rung 2: SSD-backed conversation memory via Java 21 FFM {@link MemorySegment}.
 *
 * <p>Sessions are stored as JSON files in a configurable directory, memory-mapped
 * into off-heap segments for fast access. The OS page cache handles flushing —
 * no explicit flush calls needed. Sessions survive JVM restarts (crash recovery).
 *
 * <p>Layout per session: one file per session, named {@code <sessionId>.json},
 * containing a JSON-serialized {@link ConversationContext}. The in-memory index
 * maps session IDs to their file paths for O(1) lookup.
 *
 * <p>Compared to {@code inMemory()}:
 * <ul>
 *   <li>Survives JVM restarts — crash recovery out of the box</li>
 *   <li>Off-heap — sessions don't pressure the JVM heap or GC</li>
 *   <li>OS page cache — frequently accessed sessions stay in memory automatically</li>
 *   <li>Slightly higher latency on cold reads (page fault on first access)</li>
 * </ul>
 *
 * <p>Not appropriate for multi-instance deployments — use {@code redis()} for that.
 */
public final class MappedMemoryStrategy implements MemoryStrategy {

    private static final Logger log = LoggerFactory.getLogger(MappedMemoryStrategy.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** Default storage directory. */
    public static final String DEFAULT_DIR =
        System.getProperty("java.io.tmpdir") + "/cafeai/sessions";

    private final Path  storageDir;
    private final Arena arena;

    // In-memory index: sessionId → absolute Path to the session file.
    // The actual data lives on disk; this avoids directory scanning on every lookup.
    private final ConcurrentHashMap<String, Path> index = new ConcurrentHashMap<>();

    /** Creates strategy using the default storage directory. */
    public MappedMemoryStrategy() {
        this(Path.of(DEFAULT_DIR));
    }

    /** Creates strategy using a custom storage directory. */
    public MappedMemoryStrategy(Path storageDir) {
        this.storageDir = storageDir;
        this.arena      = Arena.ofShared();  // shared — safe for concurrent access
        initialise();
    }

    private void initialise() {
        try {
            Files.createDirectories(storageDir);
            // Rebuild the in-memory index from existing session files (crash recovery)
            try (var stream = Files.list(storageDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                      .forEach(p -> {
                          String name = p.getFileName().toString();
                          String sessionId = name.substring(0, name.length() - 5); // strip .json
                          index.put(sessionId, p);
                      });
            }
            log.info("MappedMemoryStrategy: storage at {}, {} sessions recovered",
                storageDir, index.size());
        } catch (IOException e) {
            throw new MemoryInitException(
                "Cannot initialise mapped memory storage at " + storageDir, e);
        }
    }

    @Override
    public void store(String sessionId, ConversationContext context) {
        Path file = storageDir.resolve(sanitise(sessionId) + ".json");
        try {
            byte[] json = MAPPER.writeValueAsBytes(context);
            // Plain Files.write() — simple, reliable, OS page-caches automatically.
            // Memory-mapped write (READ_WRITE) requires the channel opened with READ
            // too, and creates lifetime complexity with the shared Arena. Not worth it.
            Files.write(file, json,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
            index.put(sessionId, file);
        } catch (IOException e) {
            log.error("Failed to store session {}: {}", sessionId, e.getMessage());
            throw new MemoryStoreException("Cannot store session: " + sessionId, e);
        }
    }

    @Override
    public ConversationContext retrieve(String sessionId) {
        Path file = index.get(sessionId);
        if (file == null || !Files.exists(file)) return null;
        try {
            // Memory-map for reading — OS page cache handles hot sessions
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
                long size = ch.size();
                if (size == 0) return null;
                MemorySegment segment = ch.map(
                    FileChannel.MapMode.READ_ONLY, 0, size, arena);
                byte[] json = segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                return MAPPER.readValue(json, ConversationContext.class);
            }
        } catch (IOException e) {
            log.warn("Failed to retrieve session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public void evict(String sessionId) {
        Path file = index.remove(sessionId);
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Failed to evict session file {}: {}", file, e.getMessage());
            }
        }
    }

    @Override
    public boolean exists(String sessionId) {
        Path file = index.get(sessionId);
        return file != null && Files.exists(file);
    }

    /**
     * Closes the shared {@link Arena}, releasing all mapped segments.
     * Called on application shutdown. After this, no further store/retrieve calls
     * may be made.
     */
    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
            log.debug("MappedMemoryStrategy: arena closed");
        }
    }

    /** Sanitises a session ID for use as a filename — replaces unsafe chars. */
    private static String sanitise(String sessionId) {
        return sessionId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    public static final class MemoryInitException extends RuntimeException {
        public MemoryInitException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static final class MemoryStoreException extends RuntimeException {
        public MemoryStoreException(String msg, Throwable cause) { super(msg, cause); }
    }
}
