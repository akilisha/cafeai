package io.cafeai.config;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("cafeai.usage.header")
class UsageHeaderTest {

    record Fixed() implements AiProvider, LangchainBridge.ChatModelAccess {
        @Override public String       name()    { return "fixed"; }
        @Override public String       modelId() { return "fixed-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("ok"))
                            .tokenUsage(new TokenUsage(100, 20, 120)).build();
                }
            };
        }
    }

    @Test @DisplayName("with -Dcafeai.usage.header=true, a response says what its model calls used and cost")
    void headerWhenEnabled() throws Exception {
        System.setProperty("cafeai.usage.header", "true");
        CafeAI app;
        try {
            app = CafeAI.create();
        } finally {
            System.clearProperty("cafeai.usage.header");
        }
        app.ai(new Fixed());
        app.pricing(Pricing.of("fixed-1", 1.0, 2.0));
        app.get("/ask", (req, res, next) -> res.send(app.prompt("hi").call().text()));
        app.get("/plain", (req, res, next) -> res.send("no model"));

        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            var ask = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ask")).build(),
                    HttpResponse.BodyHandlers.ofString());
            var plain = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/plain")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(ask.headers().firstValue("X-CafeAI-Usage"))
                    .hasValue("calls=1; in=100; out=20; cost=$0.000140");
            assertThat(plain.headers().firstValue("X-CafeAI-Usage")).isEmpty();
        } finally {
            app.stop();
        }
    }
}
