package io.cafeai.core.routing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Represents a single file from a {@code multipart/form-data} upload.
 *
 * <p>Accessed via {@link Request#file(String)} or {@link Request#files(String)}
 * after registering {@code app.filter(CafeAI.multipart())} globally.
 *
 * <pre>{@code
 *   app.filter(CafeAI.multipart());
 *
 *   app.post("/upload", (req, res, next) -> {
 *       UploadedFile doc = req.file("document");
 *       if (doc == null) {
 *           res.status(400).json(Map.of("error", "no file uploaded"));
 *           return;
 *       }
 *
 *       // Save to disk
 *       Path saved = doc.saveTo(Path.of("/uploads/" + doc.originalName()));
 *
 *       res.json(Map.of(
 *           "name",     doc.originalName(),
 *           "size",     doc.size(),
 *           "mimeType", doc.mimeType(),
 *           "path",     saved.toString()
 *       ));
 *   });
 * }</pre>
 */
public final class UploadedFile {

    private final String fieldName;
    private final String originalName;
    private final String mimeType;
    private final byte[] bytes;

    public UploadedFile(String fieldName, String originalName,
                        String mimeType, byte[] bytes) {
        this.fieldName    = fieldName;
        this.originalName = originalName;
        this.mimeType     = mimeType;
        this.bytes        = bytes;
    }

    /** The form field name this file was submitted under. */
    public String fieldName()    { return fieldName; }

    /** The original filename as reported by the browser. */
    public String originalName() { return originalName; }

    /** The MIME type reported by the browser (e.g. {@code image/jpeg}, {@code application/pdf}). */
    public String mimeType()     { return mimeType; }

    /** The raw file bytes. */
    public byte[] bytes()        { return bytes; }

    /** File size in bytes. */
    public long size()           { return bytes.length; }

    /**
     * Saves the uploaded file to the given path.
     *
     * @param destination the target path (including filename)
     * @return the path the file was written to
     * @throws IOException if the file cannot be written
     */
    public Path saveTo(Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.write(destination, bytes);
        return destination;
    }

    /**
     * Saves the file to a directory, using the original filename.
     *
     * @param directory the target directory (must exist or be creatable)
     * @return the full path of the saved file
     * @throws IOException if the file cannot be written
     */
    public Path saveToDirectory(Path directory) throws IOException {
        return saveTo(directory.resolve(sanitiseName(originalName)));
    }

    /**
     * Sanitises a filename for safe disk storage.
     * Strips path traversal characters and replaces spaces.
     */
    private static String sanitiseName(String name) {
        if (name == null || name.isBlank()) return "upload";
        return Path.of(name).getFileName().toString()
            .replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }

    @Override
    public String toString() {
        return "UploadedFile{name='" + originalName + "', size=" + size()
            + ", type='" + mimeType + "'}";
    }
}
