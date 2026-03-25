package io.cafeai.rag;

/**
 * A single document chunk retrieved from the vector store.
 *
 * <p>Retrieved documents are stored in
 * {@code req.attribute(Attributes.RAG_DOCUMENTS)} as a
 * {@code List<RagDocument>} and injected into the LLM context
 * before the user's message.
 *
 * <pre>{@code
 *   app.post("/ask", (req, res, next) -> {
 *       PromptResponse response = app.prompt(req.body("question")).call();
 *
 *       // Access the documents that informed the answer
 *       List<RagDocument> docs = req.attribute(Attributes.RAG_DOCUMENTS, List.class);
 *       res.json(Map.of(
 *           "answer",  response.text(),
 *           "sources", docs.stream().map(RagDocument::sourceId).toList()
 *       ));
 *   });
 * }</pre>
 */
public record RagDocument(

    /** The text content of this chunk. Injected verbatim into the LLM context. */
    String content,

    /**
     * The source identifier — file path, URL, or ID passed to {@code Source.text()}.
     * Use this to cite your sources in the response.
     */
    String sourceId,

    /**
     * Cosine similarity score [0.0, 1.0] between the query embedding and
     * this chunk's embedding. Higher = more relevant.
     */
    double score,

    /**
     * Chunk index within the original source document.
     * Useful for reconstructing document order or providing page references.
     */
    int chunkIndex

) {
    /** Convenience constructor for when chunk index is unknown. */
    public RagDocument(String content, String sourceId, double score) {
        this(content, sourceId, score, -1);
    }
}
