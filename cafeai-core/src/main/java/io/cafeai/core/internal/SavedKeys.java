package io.cafeai.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The API keys {@code cafeai login} saved, in {@code credentials.json} in the CafeAI
 * configuration directory: {@code -Dcafeai.config.dir}, else {@code $CAFEAI_CONFIG_DIR}, else
 * {@code ~/.cafeai}. The file is the user's own: on POSIX systems it is written readable by its
 * owner only; on Windows it inherits the user profile's permissions, like Claude Code's
 * {@code .credentials.json}.
 *
 * <p>A key is looked up in this order, the first found wins: the vendor's environment variable
 * ({@code OPENAI_API_KEY}, ...), then the saved key. (A provider's {@code withCredentials(...)},
 * when set, is used before either.) Public only so {@code io.cafeai.core.login} and
 * {@code io.cafeai.core.ai} can reach it.
 */
public final class SavedKeys {

    private static final Logger log = LoggerFactory.getLogger(SavedKeys.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE = "credentials.json";
    private static final String VERSION = "1.0";

    /** Vendors already logged as using their saved key, so the log says it once. */
    private static final Set<KeyVendor> announced = ConcurrentHashMap.newKeySet();

    private record Cached(Path file, FileTime modified, Map<String, String> keys) { }
    private static volatile Cached cache;

    /** Where a key came from. */
    public enum Source { ENVIRONMENT, SAVED, NONE }

    /** A key and where it came from; {@code key} is {@code null} when {@code source} is {@code NONE}. */
    public record Resolved(String key, Source source) { }

    private SavedKeys() {}

    /** The CafeAI configuration directory. */
    public static Path dir() {
        String dir = System.getProperty("cafeai.config.dir");
        if (dir == null || dir.isBlank()) dir = System.getenv("CAFEAI_CONFIG_DIR");
        if (dir == null || dir.isBlank()) return Path.of(System.getProperty("user.home"), ".cafeai");
        return Path.of(dir);
    }

    /** The keys file. */
    public static Path file() {
        return dir().resolve(FILE);
    }

    /** The vendor's key: its environment variable if set, else the saved key, else none. */
    public static Resolved resolve(KeyVendor vendor) {
        return resolve(vendor, System::getenv);
    }

    /** {@link #resolve(KeyVendor)} with the environment given, for tests. */
    public static Resolved resolve(KeyVendor vendor, UnaryOperator<String> env) {
        String fromEnv = env.apply(vendor.envVar());
        if (fromEnv != null && !fromEnv.isBlank()) return new Resolved(fromEnv, Source.ENVIRONMENT);
        String saved = read().get(vendor.id());
        return saved != null ? new Resolved(saved, Source.SAVED) : new Resolved(null, Source.NONE);
    }

    /**
     * The vendor's key for a model call, or an exception that says how to provide one.
     *
     * @param provider the provider's name, for the message
     * @param more extra lines for the message (e.g. the local, keyless alternatives), or empty
     */
    public static String require(KeyVendor vendor, String provider, String more) {
        Resolved resolved = resolve(vendor);
        if (resolved.source() == Source.NONE) {
            throw new IllegalStateException(
                "Missing API key for " + provider + " provider. Either save one once:\n\n"
                + "  cafeai login " + vendor.id() + "\n\n"
                + "or set the " + vendor.envVar() + " environment variable:\n\n"
                + "  export " + vendor.envVar() + "=your-key-here\n\n"
                + "Get a key at " + vendor.keysPage() + more);
        }
        if (resolved.source() == Source.SAVED && announced.add(vendor)) {
            log.info("{}: using the key saved by `cafeai login {}` ({})", provider, vendor.id(), file());
        }
        return resolved.key();
    }

    /** The saved key, if any (the environment is not consulted). */
    public static String saved(KeyVendor vendor) {
        return read().get(vendor.id());
    }

    /** Saves the vendor's key, replacing any saved before. */
    public static void save(KeyVendor vendor, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key must not be blank");
        Map<String, String> keys = new TreeMap<>(read());
        keys.put(vendor.id(), key);
        write(keys);
    }

    /** Forgets the vendor's saved key. Returns whether there was one. */
    public static boolean remove(KeyVendor vendor) {
        Map<String, String> keys = new TreeMap<>(read());
        if (keys.remove(vendor.id()) == null) return false;
        write(keys);
        return true;
    }

    private static Map<String, String> read() {
        Path file = file();
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            Cached c = cache;
            if (c != null && c.file().equals(file) && c.modified().equals(modified)) return c.keys();
            Map<String, String> keys = new TreeMap<>();
            JsonNode root = JSON.readTree(file.toFile());
            JsonNode saved = root == null ? null : root.get("keys");
            if (saved == null || !saved.isObject()) {
                throw new IllegalStateException("The CafeAI keys file " + file + " has no \"keys\" object. "
                        + "Fix or delete it, then run `cafeai login <vendor>` again.");
            }
            saved.properties().forEach(e -> {
                if (e.getValue().isTextual()) keys.put(e.getKey(), e.getValue().asText());
            });
            Map<String, String> result = Map.copyOf(keys);
            cache = new Cached(file, modified, result);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the CafeAI keys file " + file + ": " + e.getMessage()
                    + ". Fix or delete it, then run `cafeai login <vendor>` again.", e);
        }
    }

    private static void write(Map<String, String> keys) {
        Path dir = dir();
        Path file = dir.resolve(FILE);
        try {
            Files.createDirectories(dir);
            ownerOnly(dir, "rwx------");
            ObjectNode root = JSON.createObjectNode().put("version", VERSION);
            ObjectNode saved = root.putObject("keys");
            keys.forEach(saved::put);
            Path temp = Files.createTempFile(dir, FILE, ".tmp");
            try {
                ownerOnly(temp, "rw-------");
                JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            cache = null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the CafeAI keys file " + file, e);
        }
    }

    private static void ownerOnly(Path path, String permissions) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }
}
