package io.cafeai.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A document source for ingestion into the RAG pipeline.
 *
 * <p>Sources are passed to {@code app.ingest(source)} to parse, chunk, embed,
 * and store documents in the registered vector store.
 *
 * <pre>{@code
 *   app.ingest(Source.pdf("docs/handbook.pdf"));
 *   app.ingest(Source.text("CafeAI is a Gen AI framework for Java.", "cafeai-intro"));
 *   app.ingest(Source.directory("knowledge/"));
 *   app.ingest(Source.url("https://docs.cafeai.io/getting-started"));
 * }</pre>
 *
 * <p>Sources produce a list of {@link RawDocument}s — parsed text with a
 * stable {@code sourceId} used for idempotent upsert.
 */
public interface Source {

    /**
     * Parses the source and returns raw text documents ready for chunking.
     *
     * @throws SourceException if the source cannot be read or parsed
     */
    List<RawDocument> load() throws SourceException;

    /**
     * A stable identifier for this source — used as a prefix for chunk IDs
     * to enable idempotent re-ingestion.
     */
    String sourceId();

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Raw text source — the simplest form. Content is provided directly.
     *
     * @param content the text content
     * @param id      stable identifier for this document
     */
    static Source text(String content, String id) {
        return new TextSource(content, id);
    }

    /**
     * PDF file source — parsed via Apache Tika (via Langchain4j).
     *
     * @param path absolute or relative path to the PDF file
     */
    static Source pdf(String path) {
        return new FileSource(Path.of(path), "application/pdf");
    }

    /**
     * Plain text or markdown file source.
     *
     * @param path absolute or relative path to the file
     */
    static Source file(String path) {
        return new FileSource(Path.of(path), "text/plain");
    }

    /**
     * Directory source — recursively loads all supported files
     * ({@code .txt}, {@code .md}, {@code .pdf}).
     *
     * @param path path to the directory
     */
    static Source directory(String path) {
        return new DirectorySource(Path.of(path));
    }

    /**
     * URL source — fetches and parses the page content.
     * Requires network access at ingestion time.
     *
     * @param url the URL to fetch
     */
    static Source url(String url) {
        return new UrlSource(url);
    }

    // ── Supporting types ──────────────────────────────────────────────────────

    /**
     * A parsed document ready for chunking and embedding.
     */
    record RawDocument(String content, String sourceId) {}

    /** Thrown when a source cannot be loaded or parsed. */
    class SourceException extends RuntimeException {
        public SourceException(String message, Throwable cause) { super(message, cause); }
        public SourceException(String message)                   { super(message); }
    }

    // ── Implementations ───────────────────────────────────────────────────────

    record TextSource(String content, String id) implements Source {
        @Override public List<RawDocument> load() {
            return List.of(new RawDocument(content, id));
        }
        @Override public String sourceId() { return id; }
    }

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

    final class UrlSource implements Source {
        private final String url;

        UrlSource(String url) { this.url = url; }

        @Override
        public List<RawDocument> load() throws SourceException {
            try {
                // Use Java 21's built-in HttpClient — zero extra dependencies.
                var client  = java.net.http.HttpClient.newHttpClient();
                var request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(url))
                    .header("User-Agent", "CafeAI-RAG/0.1")
                    .GET()
                    .build();
                var response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new SourceException(
                        "HTTP " + response.statusCode() + " fetching URL: " + url);
                }
                return List.of(new RawDocument(response.body(), sourceId()));
            } catch (SourceException e) {
                throw e;
            } catch (Exception e) {
                throw new SourceException("Cannot fetch URL: " + url, e);
            }
        }

        @Override public String sourceId() { return url; }
    }
}
