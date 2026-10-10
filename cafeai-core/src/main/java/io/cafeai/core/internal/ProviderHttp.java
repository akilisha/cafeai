package io.cafeai.core.internal;

import io.cafeai.core.ai.Credentials;
import io.cafeai.core.ai.SignedCredentials;
import io.cafeai.core.routing.Request;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * The HTTP client a provider's model runs on: LangChain4j's JDK client is built from a
 * {@link HttpClient.Builder}, and {@link #builder} is one whose clients pass every call through,
 * with two additions.
 *
 * <ul>
 *   <li><b>A credential per request</b> (when given): asked for on the thread making the call, so
 *       it can be the caller's, and set as the only credential header, replacing whatever the
 *       model's client put there. An API key ({@link Credentials#apiKey()}) goes in the
 *       provider's key header; a token goes in {@code Authorization: Bearer}. This is how a
 *       provider whose LangChain4j client has no per-request header hook (Anthropic) still
 *       reaches each call with the caller's credential.</li>
 *   <li><b>A {@code 429}'s {@code Retry-After}</b>, kept for the CafeAI request the call was made
 *       for: LangChain4j's {@code RateLimitException} carries no headers, so it would be lost.
 *       The error handler sends it with CafeAI's own {@code 429}.</li>
 * </ul>
 * The request is taken when the call starts, so a streamed call, whose response arrives on
 * another thread, is covered too.
 */
final class ProviderHttp {

    /** Request attribute: the provider's {@code Retry-After}, as it sent it. */
    static final String RETRY_AFTER = "_providerRetryAfter";

    /** RFC 9110 10.2.3: delay-seconds, or an HTTP date. Anything else isn't passed on. */
    private static final Pattern VALID = Pattern.compile("\\d{1,9}|[A-Za-z]{3}, \\d{2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");

    /** Every header a provider's client might carry a credential in: all are replaced. */
    private static final Set<String> CREDENTIAL_HEADERS = Set.of("authorization", "x-api-key", "api-key");

    private ProviderHttp() {}

    /** Calls pass through as they are; a {@code 429}'s {@code Retry-After} is kept. */
    static HttpClient.Builder builder() {
        return new Builder(HttpClient.newBuilder(), null, null, null);
    }

    /**
     * As {@link #builder()}, and each call carries {@code credentials}: an API key in
     * {@code apiKeyHeader}, anything else as {@code Authorization: Bearer}.
     */
    static HttpClient.Builder builder(Credentials credentials, String apiKeyHeader) {
        return new Builder(HttpClient.newBuilder(), credentials, apiKeyHeader, null);
    }

    /** As {@link #builder(Credentials, String)}, each request first reshaped by {@code rewrite}. */
    static HttpClient.Builder builder(Credentials credentials, String apiKeyHeader, Rewrite rewrite) {
        return new Builder(HttpClient.newBuilder(), credentials, apiKeyHeader, rewrite);
    }

    /** Reshapes a request for an endpoint that takes the same API in another form. */
    interface Rewrite {
        HttpRequest rewrite(HttpRequest request, byte[] body);
    }

    /**
     * Claude on Google Cloud's Vertex AI: the Messages API, but the model goes in the URL
     * ({@code .../publishers/anthropic/models/<model>:rawPredict}, or {@code :streamRawPredict}
     * for a stream) and {@code anthropic_version} in the body, not in a header.
     */
    record VertexRewrite(String project, String location) implements Rewrite {
        private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
        static final String VERSION = "vertex-2023-10-16";

        /** The Vertex host for a location: the global one, a multi-region ({@code us}, {@code eu}), or a region's. */
        static String host(String location) {
            return switch (location) {
                case "global" -> "https://aiplatform.googleapis.com";
                case "us", "eu" -> "https://aiplatform." + location + ".rep.googleapis.com";
                default -> "https://" + location + "-aiplatform.googleapis.com";
            };
        }

        @Override
        public HttpRequest rewrite(HttpRequest request, byte[] body) {
            URI uri = request.uri();
            String path = uri.getRawPath();
            int messages = path.lastIndexOf("/v1/messages");
            if (messages < 0) return request;   // not a Messages call: leave it be
            try {
                var json = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(body);
                String model = json.remove("model").asText();
                boolean stream = json.path("stream").asBoolean(false);
                json.put("anthropic_version", VERSION);
                URI target = URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path.substring(0, messages)
                        + "/v1/projects/" + project + "/locations/" + location + "/publishers/anthropic/models/"
                        + model + (stream ? ":streamRawPredict" : ":rawPredict"));
                return HttpRequest.newBuilder(request, (name, v) -> !name.equalsIgnoreCase("anthropic-version"))
                        .uri(target)
                        .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(json)))
                        .build();
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Could not reshape the request for Vertex AI", e);
            }
        }
    }

    /** The provider's {@code Retry-After} for {@code request}, when a model call was rate-limited. */
    static Optional<String> retryAfter(Request request) {
        return request == null ? Optional.empty() : Optional.ofNullable(request.attribute(RETRY_AFTER, String.class));
    }

    /** All of a request body's bytes: a model call's body is a JSON document, published at once. */
    private static byte[] bytes(HttpRequest.BodyPublisher publisher) {
        var out = new java.io.ByteArrayOutputStream();
        var done = new CompletableFuture<byte[]>();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(java.nio.ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                out.writeBytes(chunk);
            }
            @Override public void onError(Throwable t) { done.completeExceptionally(t); }
            @Override public void onComplete() { done.complete(out.toByteArray()); }
        });
        try {
            return done.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the request body to sign it", e);
        }
    }

    private static void keepRetryAfter(Request request, HttpResponse<?> response) {
        if (request == null || response.statusCode() != 429) return;
        response.headers().firstValue("Retry-After").map(String::trim)
                .filter(v -> VALID.matcher(v).matches())
                .ifPresent(v -> request.setAttribute(RETRY_AFTER, v));
    }

    private static final class Builder implements HttpClient.Builder {
        private final HttpClient.Builder delegate;
        private final Credentials credentials;
        private final String apiKeyHeader;
        private final Rewrite rewrite;

        Builder(HttpClient.Builder delegate, Credentials credentials, String apiKeyHeader, Rewrite rewrite) {
            this.delegate = delegate;
            this.credentials = credentials;
            this.apiKeyHeader = apiKeyHeader;
            this.rewrite = rewrite;
        }

        @Override public HttpClient.Builder cookieHandler(CookieHandler h)        { delegate.cookieHandler(h); return this; }
        @Override public HttpClient.Builder connectTimeout(Duration d)            { delegate.connectTimeout(d); return this; }
        @Override public HttpClient.Builder sslContext(SSLContext c)              { delegate.sslContext(c); return this; }
        @Override public HttpClient.Builder sslParameters(SSLParameters p)        { delegate.sslParameters(p); return this; }
        @Override public HttpClient.Builder executor(Executor e)                  { delegate.executor(e); return this; }
        @Override public HttpClient.Builder followRedirects(HttpClient.Redirect r) { delegate.followRedirects(r); return this; }
        @Override public HttpClient.Builder version(HttpClient.Version v)         { delegate.version(v); return this; }
        @Override public HttpClient.Builder priority(int p)                       { delegate.priority(p); return this; }
        @Override public HttpClient.Builder proxy(ProxySelector p)                { delegate.proxy(p); return this; }
        @Override public HttpClient.Builder authenticator(Authenticator a)        { delegate.authenticator(a); return this; }
        @Override public HttpClient.Builder localAddress(InetAddress a)           { delegate.localAddress(a); return this; }
        @Override public HttpClient build() { return new Client(delegate.build(), credentials, apiKeyHeader, rewrite); }
    }

    private static final class Client extends HttpClient {
        private final HttpClient delegate;
        private final Credentials credentials;
        private final String apiKeyHeader;
        private final Rewrite rewrite;

        Client(HttpClient delegate, Credentials credentials, String apiKeyHeader, Rewrite rewrite) {
            this.delegate = delegate;
            this.credentials = credentials;
            this.apiKeyHeader = apiKeyHeader;
            this.rewrite = rewrite;
        }

        /**
         * {@code request} reshaped for its endpoint, if it needs it, then carrying this call's
         * credential and no other. Asked for now, on this thread.
         */
        private HttpRequest credentialed(HttpRequest request) {
            if (rewrite != null) {
                request = rewrite.rewrite(request, request.bodyPublisher().map(ProviderHttp::bytes).orElse(new byte[0]));
            }
            if (credentials == null) return request;
            if (credentials instanceof SignedCredentials signer) return signed(request, signer);
            String value = credentials.token();
            var copy = HttpRequest.newBuilder(request, (name, v) -> !CREDENTIAL_HEADERS.contains(name.toLowerCase()));
            if (credentials.apiKey() && apiKeyHeader != null) copy.header(apiKeyHeader, value);
            else copy.header("Authorization", "Bearer " + value);
            return copy.build();
        }

        /**
         * {@code request} signed by {@code signer}: its body is read so the signature can cover it,
         * and sent as those very bytes. {@code host} is signed as the client will send it (the
         * port only when it isn't the scheme's default); the client sets that header itself.
         */
        private HttpRequest signed(HttpRequest request, SignedCredentials signer) {
            byte[] body = request.bodyPublisher().map(ProviderHttp::bytes).orElse(new byte[0]);
            URI uri = request.uri();
            int port = uri.getPort();
            boolean defaultPort = port == -1 || (port == 443 && "https".equals(uri.getScheme()))
                    || (port == 80 && "http".equals(uri.getScheme()));
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("host", defaultPort ? uri.getHost() : uri.getHost() + ":" + port);
            request.headers().map().forEach((name, values) -> {
                if (!CREDENTIAL_HEADERS.contains(name.toLowerCase()) && !values.isEmpty()) {
                    headers.put(name.toLowerCase(), String.join(",", values));
                }
            });
            var copy = HttpRequest.newBuilder(request, (name, v) -> !CREDENTIAL_HEADERS.contains(name.toLowerCase()))
                    .method(request.method(), HttpRequest.BodyPublishers.ofByteArray(body));
            signer.sign(request.method(), uri, headers, body).forEach(copy::setHeader);
            return copy.build();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            Request caller = CurrentRequest.get().orElse(null);
            HttpResponse<T> response = delegate.send(credentialed(request), handler);
            keepRetryAfter(caller, response);
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            Request caller = CurrentRequest.get().orElse(null);
            return delegate.sendAsync(credentialed(request), handler).thenApply(r -> { keepRetryAfter(caller, r); return r; });
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> push) {
            Request caller = CurrentRequest.get().orElse(null);
            return delegate.sendAsync(credentialed(request), handler, push).thenApply(r -> { keepRetryAfter(caller, r); return r; });
        }

        @Override public Optional<CookieHandler> cookieHandler()   { return delegate.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout()       { return delegate.connectTimeout(); }
        @Override public Redirect followRedirects()                { return delegate.followRedirects(); }
        @Override public Optional<ProxySelector> proxy()           { return delegate.proxy(); }
        @Override public SSLContext sslContext()                   { return delegate.sslContext(); }
        @Override public SSLParameters sslParameters()             { return delegate.sslParameters(); }
        @Override public Optional<Authenticator> authenticator()   { return delegate.authenticator(); }
        @Override public Version version()                         { return delegate.version(); }
        @Override public Optional<Executor> executor()             { return delegate.executor(); }
        @Override public void shutdown()                           { delegate.shutdown(); }
        @Override public void shutdownNow()                        { delegate.shutdownNow(); }
        @Override public boolean isTerminated()                    { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(Duration d) throws InterruptedException { return delegate.awaitTermination(d); }
        @Override public void close()                              { delegate.close(); }
    }
}
