package io.cafeai.core.internal;

import io.cafeai.core.ai.Credentials;
import io.cafeai.core.routing.Request;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
        return new Builder(HttpClient.newBuilder(), null, null);
    }

    /**
     * As {@link #builder()}, and each call carries {@code credentials}: an API key in
     * {@code apiKeyHeader}, anything else as {@code Authorization: Bearer}.
     */
    static HttpClient.Builder builder(Credentials credentials, String apiKeyHeader) {
        return new Builder(HttpClient.newBuilder(), credentials, apiKeyHeader);
    }

    /** The provider's {@code Retry-After} for {@code request}, when a model call was rate-limited. */
    static Optional<String> retryAfter(Request request) {
        return request == null ? Optional.empty() : Optional.ofNullable(request.attribute(RETRY_AFTER, String.class));
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

        Builder(HttpClient.Builder delegate, Credentials credentials, String apiKeyHeader) {
            this.delegate = delegate;
            this.credentials = credentials;
            this.apiKeyHeader = apiKeyHeader;
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
        @Override public HttpClient build() { return new Client(delegate.build(), credentials, apiKeyHeader); }
    }

    private static final class Client extends HttpClient {
        private final HttpClient delegate;
        private final Credentials credentials;
        private final String apiKeyHeader;

        Client(HttpClient delegate, Credentials credentials, String apiKeyHeader) {
            this.delegate = delegate;
            this.credentials = credentials;
            this.apiKeyHeader = apiKeyHeader;
        }

        /** {@code request} carrying this call's credential, and no other. Asked for now, on this thread. */
        private HttpRequest credentialed(HttpRequest request) {
            if (credentials == null) return request;
            String value = credentials.token();
            var copy = HttpRequest.newBuilder(request, (name, v) -> !CREDENTIAL_HEADERS.contains(name.toLowerCase()));
            if (credentials.apiKey() && apiKeyHeader != null) copy.header(apiKeyHeader, value);
            else copy.header("Authorization", "Bearer " + value);
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
