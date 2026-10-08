package io.cafeai.observability;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.Attributes;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.internal.LangchainBridge;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The verified caller on spans, and never on metrics")
class ObserveIdentityTest {

    private static final String ISSUER = "https://issuer.example.com";

    /** Fixed tokens; streams from its own thread, like a real provider. */
    private static final class Model implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()    { return "fake"; }
        @Override public String       modelId() { return "gpt-4o-mini"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        private static ChatResponse reply() {
            return ChatResponse.builder().aiMessage(AiMessage.from("pong")).tokenUsage(new TokenUsage(5, 3)).build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) { return reply(); }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    Thread.ofPlatform().start(() -> {
                        handler.onPartialResponse("pong");
                        handler.onCompleteResponse(reply());
                    });
                }
            };
        }
    }

    @BeforeEach
    void setUp() {
        TestOtel.reset();
    }

    @Test @DisplayName("chat spans carry enduser.id and the issuer, for calls and for streams")
    void callerOnSpans() throws Exception {
        var app = CafeAI.create();
        app.ai(new Model());
        app.observe(ObserveStrategy.otel());
        // Stands in for cafeai-identity's Auth.bearer, which sets the same attribute.
        app.filter((req, res, next) -> {
            String who = req.header("X-Test-Subject");
            if (who != null) {
                req.setAttribute(Attributes.IDENTITY, Identity.builder(ISSUER, who)
                        .expiresAt(Instant.now().plusSeconds(3600)).build());
            }
            next.run();
        });
        app.get("/ask", (req, res, next) -> res.send(app.prompt("ping").call().text()));
        app.get("/sse", (req, res, next) -> res.stream(app.prompt("ping")));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            get(app, "/ask", "alice");
            get(app, "/sse", "bob");
            get(app, "/ask", null);

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (chatSpans().size() < 3 && System.nanoTime() < deadline) Thread.sleep(20);
            List<SpanData> spans = chatSpans();
            assertThat(spans).hasSize(3);

            assertThat(spans.stream().map(s -> TestOtel.attrs(s).get("enduser.id")))
                    .containsExactlyInAnyOrder("alice", "bob", null);
            assertThat(spans.stream().filter(s -> TestOtel.attrs(s).containsKey("enduser.id"))
                    .map(s -> TestOtel.attrs(s).get("cafeai.enduser.issuer")))
                    .containsOnly(ISSUER);

            assertThat(TestOtel.metrics()).allSatisfy(metric ->
                    assertThat(metric.toString()).doesNotContain("enduser").doesNotContain("alice"));
        } finally {
            app.stop();
        }
    }

    private static List<SpanData> chatSpans() {
        return TestOtel.spans().stream().filter(s -> s.getName().equals("chat")).toList();
    }

    private static void get(CafeAI app, String path, String subject) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
        if (subject != null) request.header("X-Test-Subject", subject);
        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
