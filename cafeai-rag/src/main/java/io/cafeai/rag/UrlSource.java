package io.cafeai.rag;

import io.cafeai.core.rag.Source;

import java.util.List;

/**
 * URL source — fetches and parses page content via the JDK's built-in
 * {@code HttpClient}.
 *
 * <p>Package-private — obtained via {@code Source.url(url)}, routed through
 * {@link CafeAIRagProviderImpl}.
 */
final class UrlSource implements Source {
    private final String url;

    UrlSource(String url) { this.url = url; }

    @Override
    public List<RawDocument> load() throws SourceException {
        try {
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
