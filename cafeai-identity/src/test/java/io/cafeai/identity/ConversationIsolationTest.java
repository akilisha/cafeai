package io.cafeai.identity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Conversation memory belongs to the caller who started it")
class ConversationIsolationTest {

    private static final String AUDIENCE = "chat-api";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Answers with how many messages it was sent, so a test can see whose history arrived. */
    record Counting() implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()    { return "counting"; }
        @Override public String       modelId() { return "counting-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        static ChatResponse answer(ChatRequest r) {
            return ChatResponse.builder().aiMessage(AiMessage.from("n=" + r.messages().size())).build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) { return answer(r); }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    // From another thread, as a real provider answers.
                    Thread.ofPlatform().start(() -> {
                        ChatResponse a = answer(r);
                        h.onPartialResponse(a.aiMessage().text());
                        h.onCompleteResponse(a);
                    });
                }
            };
        }
    }

    private static FakeIssuer fake;
    private CafeAI app;

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start();
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @BeforeEach
    void startApp() throws Exception {
        app = CafeAI.create();
        app.ai(new Counting());
        app.memory(MemoryStrategy.inMemory());
        app.filter(Auth.bearer(fake.issuer(), AUDIENCE).optional());
        app.get("/ask", (req, res, next) -> {
            try {
                res.send(app.prompt("hi").session(req.query("s")).call().text());
            } catch (IllegalArgumentException e) {
                res.status(400).send(e.getMessage());
            }
        });
        app.get("/stream", (req, res, next) -> {
            StringBuilder out = new StringBuilder();
            app.prompt("hi").session(req.query("s")).stream(out::append);
            res.send(out.toString());
        });
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @AfterEach
    void stopApp() {
        app.stop();
    }

    private String ask(String path, String session, String subject) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                "http://localhost:" + app.port() + path + "?s=" + java.net.URLEncoder.encode(session, "UTF-8")));
        if (subject != null) {
            request.header("Authorization", "Bearer " + fake.token().subject(subject).audience(AUDIENCE).sign());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test @DisplayName("another caller naming the same conversation starts their own, and never sees the first")
    void callersAreIsolated() throws Exception {
        assertThat(ask("/ask", "s-1", "alice")).isEqualTo("n=1");
        assertThat(ask("/ask", "s-1", "alice")).isEqualTo("n=3");   // her history: hi, n=1, hi

        assertThat(ask("/ask", "s-1", "bob")).isEqualTo("n=1");     // not Alice's conversation
        assertThat(ask("/ask", "s-1", "alice")).isEqualTo("n=5");   // and Bob didn't add to hers
    }

    @Test @DisplayName("a streamed reply, saved on the provider's thread, goes to the caller's conversation")
    void streamsStayWithTheCaller() throws Exception {
        assertThat(ask("/stream", "s-2", "carol")).isEqualTo("n=1");
        assertThat(ask("/ask", "s-2", "carol")).isEqualTo("n=3");
        assertThat(ask("/stream", "s-2", "dave")).isEqualTo("n=1");
        assertThat(ask("/ask", "s-2", null)).isEqualTo("n=1");       // nor is it the anonymous "s-2"
    }

    @Test @DisplayName("anonymous callers keep the conversation id as given, but can't name a scoped one")
    void anonymous() throws Exception {
        assertThat(ask("/ask", "s-3", null)).isEqualTo("n=1");
        assertThat(ask("/ask", "s-3", null)).isEqualTo("n=3");
        assertThat(ask("/ask", "cafeai-identity:abc:s-3", null)).contains("reserved");
    }
}
