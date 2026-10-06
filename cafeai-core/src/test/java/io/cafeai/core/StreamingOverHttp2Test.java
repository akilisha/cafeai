package io.cafeai.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A token stream reaches a client over HTTP/2 (cleartext upgrade, as Java's HttpClient
 * attempts by default) as well as HTTP/1.1. It once carried a {@code Connection}
 * header, which HTTP/2 forbids, and the client reset the stream.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("res.stream(...) over HTTP/2 and HTTP/1.1")
class StreamingOverHttp2Test {

    record Tokens() implements AiProvider, LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()    { return "tokens"; }
        @Override public String       modelId() { return "tokens-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("Hello")).build();
                }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    h.onPartialResponse("Hel");
                    h.onPartialResponse("lo");
                    h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello")).build());
                }
            };
        }
    }

    private CafeAI app;

    @BeforeAll
    void start() throws Exception {
        app = CafeAI.create();
        app.ai(new Tokens());
        app.get("/sse", (req, res, next) -> res.stream(app.prompt("say hello")));
        var latch = new CountDownLatch(1);
        app.listen(0, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterAll
    void stop() {
        if (app != null) app.stop();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HttpClient.Version.class)
    void streams(HttpClient.Version version) throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder().version(version).build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/sse")).build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.version()).as("the protocol actually used").isEqualTo(version);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/event-stream"));
        assertThat(response.body()).contains("data: Hel\n\n").contains("data: lo\n\n");
    }
}
