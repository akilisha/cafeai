package io.cafeai.core.internal;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * Keeps a model provider's {@code Retry-After} for the request that was rate-limited.
 *
 * <p>LangChain4j turns a {@code 429} into a {@code RateLimitException} that carries no headers,
 * so the provider's word on when to come back would be lost. LangChain4j's HTTP client is built
 * from a {@link HttpClient.Builder}; {@link #builder()} is one whose client passes every call
 * through unchanged, and on a {@code 429} puts the {@code Retry-After} on the CafeAI request it
 * was made for. The request is taken when the call starts, so a streamed call, whose response
 * arrives on another thread, is covered too. The error handler then sends it with its own
 * {@code 429}.
 */
final class RetryAfterCapture {

    /** Request attribute: the provider's {@code Retry-After}, as it sent it. */
    static final String ATTRIBUTE = "_providerRetryAfter";

    /** RFC 9110 10.2.3: delay-seconds, or an HTTP date. Anything else isn't passed on. */
    private static final Pattern VALID = Pattern.compile("\\d{1,9}|[A-Za-z]{3}, \\d{2} [A-Za-z]{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT");

    private RetryAfterCapture() {}

    /** An HTTP client builder whose clients keep a {@code 429}'s {@code Retry-After}. */
    static HttpClient.Builder builder() {
        return new Builder(HttpClient.newBuilder());
    }

    /** The provider's {@code Retry-After} for {@code request}, when a model call was rate-limited. */
    static Optional<String> of(Request request) {
        return request == null ? Optional.empty() : Optional.ofNullable(request.attribute(ATTRIBUTE, String.class));
    }

    private static void keep(Request request, HttpResponse<?> response) {
        if (request == null || response.statusCode() != 429) return;
        response.headers().firstValue("Retry-After").map(String::trim)
                .filter(v -> VALID.matcher(v).matches())
                .ifPresent(v -> request.setAttribute(ATTRIBUTE, v));
    }

    private static final class Builder implements HttpClient.Builder {
        private final HttpClient.Builder delegate;

        Builder(HttpClient.Builder delegate) { this.delegate = delegate; }

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
        @Override public HttpClient build()                                       { return new Client(delegate.build()); }
    }

    private static final class Client extends HttpClient {
        private final HttpClient delegate;

        Client(HttpClient delegate) { this.delegate = delegate; }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            Request caller = CurrentRequest.get().orElse(null);
            HttpResponse<T> response = delegate.send(request, handler);
            keep(caller, response);
            return response;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            Request caller = CurrentRequest.get().orElse(null);
            return delegate.sendAsync(request, handler).thenApply(r -> { keep(caller, r); return r; });
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                HttpResponse.PushPromiseHandler<T> push) {
            Request caller = CurrentRequest.get().orElse(null);
            return delegate.sendAsync(request, handler, push).thenApply(r -> { keep(caller, r); return r; });
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
