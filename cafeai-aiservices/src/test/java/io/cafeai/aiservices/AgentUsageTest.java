package io.cafeai.aiservices;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Pricing;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent usage is credited to the route that called the agent")
class AgentUsageTest {

    interface Assistant {
        String chat(String message);
    }

    record Fixed() implements AiProvider, LangchainBridge.ChatModelAccess {
        @Override public String       name()    { return "fixed"; }
        @Override public String       modelId() { return "fixed-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("ok"))
                            .tokenUsage(new TokenUsage(50, 10, 60)).build();
                }
            };
        }
    }

    @Test
    void agentCallsCountForTheirRoute() throws Exception {
        var app = CafeAI.create();
        app.ai(new Fixed());
        app.pricing(Pricing.of("fixed-1", 1.0, 1.0));
        app.agent("helper", Assistant.class);
        app.get("/help", (req, res, next) ->
                res.send(app.agent("helper", Assistant.class, null).chat("hello")));

        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            for (int i = 0; i < 2; i++) {
                String body = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/help")).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                assertThat(body).isEqualTo("ok");
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (app.usage().route("GET /help").map(r -> r.calls() < 2).orElse(true) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            var help = app.usage().route("GET /help").orElseThrow();
            assertThat(help.calls()).isEqualTo(2);
            assertThat(help.inputTokens()).isEqualTo(100);
            assertThat(help.cost()).isCloseTo(2 * 60 / 1_000_000d, org.assertj.core.data.Offset.offset(1e-12));
        } finally {
            app.stop();
        }
    }
}
