package io.cafeai.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Pricing;
import io.cafeai.core.ai.UsageReport;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Usage and cost, per route")
class UsageTest {

    /** Answers every call with fixed token usage: 100 in, 20 out. */
    record Fixed(String modelId) implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name() { return "fixed"; }
        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        static ChatResponse answer() {
            return ChatResponse.builder().aiMessage(AiMessage.from("ok"))
                    .tokenUsage(new TokenUsage(100, 20, 120)).build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) { return answer(); }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    h.onPartialResponse("o");
                    h.onPartialResponse("k");
                    h.onCompleteResponse(answer());
                }
            };
        }
    }

    private static final double ONE_CALL = (100 * 1.0 + 20 * 2.0) / 1_000_000;   // fixed-1 at $1 / $2 per million

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private CafeAI started(CafeAI app) throws Exception {
        var latch = new CountDownLatch(1);
        app.listen(0, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        return app;
    }

    private String get(CafeAI app, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    @Test @DisplayName("calls are credited to the route pattern, so different ids are one route")
    void creditedToTheRoutePattern() throws Exception {
        var app = CafeAI.create();
        app.ai(new Fixed("fixed-1"));
        app.pricing(Pricing.of("fixed-1", 1.0, 2.0));
        app.get("/orders/:id", (req, res, next) -> {
            app.prompt("first").call();
            app.prompt("second").call();
            res.json(Map.of("route", req.route().path()));
        });
        started(app);
        try {
            assertThat(get(app, "/orders/1")).contains("\"route\":\"/orders/:id\"");
            get(app, "/orders/2");

            await(() -> app.usage().route("GET /orders/:id").map(r -> r.calls() == 4).orElse(false));
            UsageReport.RouteUsage orders = app.usage().route("GET /orders/:id").orElseThrow();
            assertThat(orders.inputTokens()).isEqualTo(400);
            assertThat(orders.outputTokens()).isEqualTo(80);
            assertThat(orders.costKnown()).isTrue();
            assertThat(orders.cost()).isCloseTo(4 * ONE_CALL, org.assertj.core.data.Offset.offset(1e-12));
            assertThat(orders.costPerCall()).hasValueSatisfying(c ->
                    assertThat(c).isCloseTo(ONE_CALL, org.assertj.core.data.Offset.offset(1e-12)));
            assertThat(app.usage().routes()).hasSize(1);
        } finally {
            app.stop();
        }
    }

    @Test @DisplayName("an unpriced model's tokens are counted and its cost is unknown, not zero")
    void unpricedModels() throws Exception {
        var app = CafeAI.create();
        app.ai(new Fixed("unpriced-model"));
        app.pricing(Pricing.of("fixed-1", 1.0, 2.0));
        app.get("/ask", (req, res, next) -> res.send(app.prompt("hi").call().text()));
        started(app);
        try {
            get(app, "/ask");
            await(() -> app.usage().route("GET /ask").isPresent());
            UsageReport.RouteUsage ask = app.usage().route("GET /ask").orElseThrow();
            assertThat(ask.inputTokens()).isEqualTo(100);
            assertThat(ask.unpricedCalls()).isEqualTo(1);
            assertThat(ask.costKnown()).isFalse();
            assertThat(ask.costPerCall()).isEmpty();
        } finally {
            app.stop();
        }
    }

    @Test @DisplayName("a streamed answer is credited once the stream completes")
    void streamsCount() throws Exception {
        var app = CafeAI.create();
        app.ai(new Fixed("fixed-1"));
        app.pricing(Pricing.of("fixed-1", 1.0, 2.0));
        app.get("/stream", (req, res, next) -> res.stream(app.prompt("hi")));
        started(app);
        try {
            assertThat(get(app, "/stream")).contains("o").contains("k");
            await(() -> app.usage().route("GET /stream").isPresent());
            assertThat(app.usage().route("GET /stream").orElseThrow().calls()).isEqualTo(1);
        } finally {
            app.stop();
        }
    }

    @Test @DisplayName("a call a path-scoped filter makes is credited to the route the request reaches")
    void filterCallsCount() throws Exception {
        var app = CafeAI.create();
        app.ai(new Fixed("fixed-1"));
        app.filter("/api", (req, res, next) -> { app.prompt("screen this").call(); next.run(); });
        app.get("/api/items", (req, res, next) -> res.send("items"));
        started(app);
        try {
            get(app, "/api/items");
            await(() -> app.usage().route("GET /api/items").isPresent());
            assertThat(app.usage().route("GET /api/items").orElseThrow().calls()).isEqualTo(1);
        } finally {
            app.stop();
        }
    }

    @Test @DisplayName("a call outside any request is counted under (no request)")
    void outsideRequests() {
        var app = CafeAI.create();
        app.ai(new Fixed("fixed-1"));
        app.prompt("warm up").call();

        UsageReport.RouteUsage none = app.usage().route(UsageReport.NO_REQUEST).orElseThrow();
        assertThat(none.calls()).isEqualTo(1);
        assertThat(app.usage().total().inputTokens()).isEqualTo(100);
    }

    @Test @DisplayName("pricing matches an exact id, else the longest priced prefix of a dated model name")
    void pricingMatches() {
        Pricing p = Pricing.of("gpt-4o", 2.5, 10).and("gpt-4o-mini", 0.15, 0.60);
        assertThat(p.cost("gpt-4o-mini-2024-07-18", 1_000_000, 0)).hasValue(0.15);
        assertThat(p.cost("gpt-4o-2024-08-06", 1_000_000, 0)).hasValue(2.5);
        assertThat(p.cost("claude-sonnet-4-5", 1_000_000, 0)).isEmpty();
    }
}
