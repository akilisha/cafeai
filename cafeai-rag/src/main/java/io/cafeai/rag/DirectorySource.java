package io.cafeai.rag;

import io.cafeai.core.rag.Source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Directory source — recursively loads {@code .txt}, {@code .md}, {@code .pdf}.
 *
 * <p>Package-private — obtained via {@code Source.directory(path)}, routed
 * through {@link CafeAIRagProviderImpl}.
 */
final class DirectorySource implements Source {
    private static final List<String> SUPPORTED = List.of(".txt", ".md", ".pdf");
    private final Path dir;

    DirectorySource(Path dir) { this.dir = dir; }

    @Override
    public List<RawDocument> load() throws SourceException {
        if (!Files.isDirectory(dir)) {
            throw new SourceException("Not a directory: " + dir);
        }
        List<RawDocument> docs = new ArrayList<>();
        try (var walker = Files.walk(dir)) {
            walker.filter(p -> SUPPORTED.stream()
                                   .anyMatch(ext -> p.toString().endsWith(ext)))
                  .forEach(p -> {
                      String ext = p.toString().endsWith(".pdf")
                          ? "application/pdf" : "text/plain";
                      try {
                          docs.addAll(new FileSource(p, ext).load());
                      } catch (SourceException e) {
                          // Log and skip unreadable files rather than aborting
                          System.err.println("Skipping " + p + ": " + e.getMessage());
                      }
                  });
        } catch (IOException e) {
            throw new SourceException("Cannot walk directory: " + dir, e);
        }
        return docs;
    }

    @Override public String sourceId() {
        return dir.toAbsolutePath().toString();
    }
}
