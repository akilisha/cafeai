package io.cafeai.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("cafeai.stream.screen=off")
class StreamScreenOffTest {

    static final List<String> TOKENS = List.of("Fine. ", "Has a sec", "ret.");

    record Tokens() implements AiProvider, LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()    { return "tokens"; }
        @Override public String       modelId() { return "tokens-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) {
                    return ChatResponse.builder().aiMessage(AiMessage.from(String.join("", TOKENS))).build();
                }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    TOKENS.forEach(h::onPartialResponse);
                    h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(String.join("", TOKENS))).build());
                }
            };
        }
    }

    record Rail() implements GuardRail {
        @Override public String name() { return "no-secrets"; }
        @Override public Position position() { return Position.POST_LLM; }
        @Override public Action action() { return Action.BLOCK; }
        @Override public void handle(Request req, Response res, Next next) { next.run(); }
        @Override public OutputCheckResult checkOutput(String text) {
            return text.contains("secret") ? OutputCheckResult.violation("flagged") : OutputCheckResult.pass();
        }
    }

    @Test @DisplayName("turns screening off: tokens are sent as they arrive, and the guardrail screens only the end")
    void offSendsTokensAsTheyArrive() {
        System.setProperty("cafeai.stream.screen", "off");
        try {
            var app = CafeAI.create();
            app.ai(new Tokens());
            app.guard(new Rail());
            List<String> chunks = new ArrayList<>();
            app.prompt("tell me").stream(chunks::add);
            assertThat(chunks).containsExactlyElementsOf(TOKENS);
        } finally {
            System.clearProperty("cafeai.stream.screen");
        }
    }
}
