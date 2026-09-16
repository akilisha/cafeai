package io.cafeai.core.rag;

import io.cafeai.core.spi.RagProvider;

import java.util.List;
import java.util.ServiceLoader;

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
 *
 * <p>{@code pdf}/{@code file}/{@code directory}/{@code url} require
 * {@code com.akilisha.oss:cafeai-rag} on the classpath — {@code text()} does not.
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
     * PDF file source — parsed via Apache Tika.
     *
     * @param path absolute or relative path to the PDF file
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static Source pdf(String path) {
        return loadProvider().pdfSource(path);
    }

    /**
     * Plain text or markdown file source.
     *
     * @param path absolute or relative path to the file
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static Source file(String path) {
        return loadProvider().fileSource(path);
    }

    /**
     * Directory source — recursively loads all supported files
     * ({@code .txt}, {@code .md}, {@code .pdf}).
     *
     * @param path path to the directory
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static Source directory(String path) {
        return loadProvider().directorySource(path);
    }

    /**
     * URL source — fetches and parses the page content.
     * Requires network access at ingestion time.
     *
     * @param url the URL to fetch
     * @throws RagModuleNotFoundException if {@code cafeai-rag} is absent
     */
    static Source url(String url) {
        return loadProvider().urlSource(url);
    }

    // ── ServiceLoader discovery ──────────────────────────────────────────────

    private static RagProvider loadProvider() {
        return ServiceLoader.load(RagProvider.class)
            .findFirst()
            .orElseThrow(() -> new RagModuleNotFoundException(
                "PDF/file/directory/URL sources require the cafeai-rag module. " +
                "Add the following dependency:\n\n" +
                "  Gradle: implementation 'com.akilisha.oss:cafeai-rag'\n" +
                "  Maven:  <artifactId>cafeai-rag</artifactId>\n\n" +
                "For development, use Source.text(content, id) (zero dependencies)."));
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

    // ── Implementation ────────────────────────────────────────────────────────

    record TextSource(String content, String id) implements Source {
        @Override public List<RawDocument> load() {
            return List.of(new RawDocument(content, id));
        }
        @Override public String sourceId() { return id; }
    }
}
