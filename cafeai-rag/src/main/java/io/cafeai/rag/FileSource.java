package io.cafeai.rag;

import io.cafeai.core.rag.Source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PDF (via Apache Tika) or plain-text/markdown file source.
 *
 * <p>Package-private — obtained via {@code Source.pdf(path)} / {@code Source.file(path)},
 * routed through {@link CafeAIRagProviderImpl}.
 */
final class FileSource implements Source {
    private final Path   path;
    private final String mimeType;

    FileSource(Path path, String mimeType) {
        this.path     = path;
        this.mimeType = mimeType;
    }

    @Override
    public List<RawDocument> load() throws SourceException {
        if (!Files.exists(path)) {
            throw new SourceException("File not found: " + path);
        }
        try {
            if ("application/pdf".equals(mimeType)) {
                return loadPdf();
            } else {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                return List.of(new RawDocument(content, sourceId()));
            }
        } catch (IOException e) {
            throw new SourceException("Cannot read file: " + path, e);
        }
    }

    private List<RawDocument> loadPdf() throws SourceException {
        try {
            // Use Apache Tika directly — no Langchain4j loader needed.
            // Tika is on the classpath via langchain4j-document-parser-apache-tika.
            org.apache.tika.Tika tika = new org.apache.tika.Tika();
            String text = tika.parseToString(path.toFile());
            return List.of(new RawDocument(text, sourceId()));
        } catch (Exception e) {
            throw new SourceException("Cannot parse PDF: " + path, e);
        }
    }

    @Override public String sourceId() {
        return path.toAbsolutePath().toString();
    }
}
