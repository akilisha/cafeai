package io.cafeai.aiservices;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.cafeai.core.Attributes;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A stateful agent's conversation belongs to the caller who started it")
class AgentConversationIsolationTest {

    interface Assistant {
        String chat(String message);
    }

    /** Answers with how many messages it was sent. */
    private static final class Counting implements AiProvider, LangchainBridge.ChatModelAccess {
        @Override public String       name()    { return "counting"; }
        @Override public String       modelId() { return "counting-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("n=" + r.messages().size())).build();
                }
            };
        }
    }

    @Test @DisplayName("callers naming the same session get separate agents and separate memory")
    void separatePerCaller() throws Exception {
        var app = CafeAI.create();
        app.ai(new Counting());
        app.agent("assistant", Assistant.class).memory(MemoryStrategy.inMemory());
        // Stands in for cafeai-identity's Auth.bearer, which sets the same attribute.
        app.filter((req, res, next) -> {
            String who = req.header("X-Test-Subject");
            if (who != null) {
                req.setAttribute(Attributes.IDENTITY, Identity.builder("https://issuer.example.com", who)
                        .expiresAt(Instant.now().plusSeconds(3600)).build());
            }
            next.run();
        });
        app.get("/chat", (req, res, next) ->
                res.send(app.agent("assistant", Assistant.class, "s-1").chat("hi")));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(chat(app, "alice")).isEqualTo("n=1");
            assertThat(chat(app, "alice")).isEqualTo("n=3");
            assertThat(chat(app, "bob")).isEqualTo("n=1");
            assertThat(chat(app, null)).isEqualTo("n=1");
            assertThat(chat(app, "alice")).isEqualTo("n=5");
        } finally {
            app.stop();
        }
    }

    private static String chat(CafeAI app, String subject) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/chat"));
        if (subject != null) request.header("X-Test-Subject", subject);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
