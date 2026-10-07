package io.cafeai.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Screened streaming")
class ScreenedStreamingTest {

    /** Streams fixed tokens; "secret" only appears once two tokens are joined. */
    record Tokens(List<String> tokens) implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()    { return "tokens"; }
        @Override public String       modelId() { return "tokens-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        ChatResponse whole() {
            return ChatResponse.builder().aiMessage(AiMessage.from(String.join("", tokens))).build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) { return whole(); }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    tokens.forEach(h::onPartialResponse);
                    h.onCompleteResponse(whole());
                }
            };
        }
    }

    record Rail(String name, Position position, Action action, String flags) implements GuardRail {
        @Override public void handle(Request req, Response res, Next next) { next.run(); }
        @Override public OutputCheckResult checkOutput(String text) {
            return text.contains(flags) ? OutputCheckResult.violation("flagged") : OutputCheckResult.pass();
        }
    }

    static final List<String> LEAKY = List.of(
            "First sentence is fine. ", "Second has the sec", "ret code 4242. ", "Third is never sent.");

    private static String sse(CafeAI app) throws Exception {
        app.get("/sse", (req, res, next) -> res.stream(app.prompt("tell me")));
        var latch = new CountDownLatch(1);
        app.listen(0, latch::countDown);
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/sse")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } finally {
            app.stop();
        }
    }

    @Test @DisplayName("a blocked answer stops at the last clean sentence: the flagged text never reaches the client")
    void blockedTextNeverLeaves() throws Exception {
        var app = CafeAI.create();
        app.ai(new Tokens(LEAKY));
        app.guard(new Rail("no-secrets", GuardRail.Position.POST_LLM, GuardRail.Action.BLOCK, "secret"));

        String body = sse(app);

        assertThat(StreamedNewlinesTest.sseEvents(body)).containsExactly(
                "First sentence is fine. ",
                "[Response blocked by guardrail: no-secrets]",
                "[DONE]");
        assertThat(body).doesNotContain("the sec").doesNotContain("4242").doesNotContain("Third");
    }

    @Test @DisplayName("a guardrail that only warns lets the whole answer through, a sentence at a time")
    void warningsPass() throws Exception {
        var app = CafeAI.create();
        app.ai(new Tokens(LEAKY));
        app.guard(new Rail("note-secrets", GuardRail.Position.POST_LLM, GuardRail.Action.WARN, "secret"));

        assertThat(StreamedNewlinesTest.sseEvents(sse(app))).containsExactly(
                "First sentence is fine. ",
                "Second has the secret code 4242. ",
                "Third is never sent.",
                "[DONE]");
    }

    @Test @DisplayName("without output guardrails, tokens are sent as they arrive, as before")
    void unscreenedWithoutOutputGuardrails() throws Exception {
        var app = CafeAI.create();
        app.ai(new Tokens(LEAKY));

        List<String> expected = new ArrayList<>(LEAKY);
        expected.add("[DONE]");
        assertThat(StreamedNewlinesTest.sseEvents(sse(app))).containsExactlyElementsOf(expected);
    }

    @Test @DisplayName("stream(consumer) is screened the same way")
    void consumerStreamIsScreened() {
        var app = CafeAI.create();
        app.ai(new Tokens(LEAKY));
        app.guard(new Rail("no-secrets", GuardRail.Position.POST_LLM, GuardRail.Action.BLOCK, "secret"));

        List<String> chunks = new ArrayList<>();
        app.prompt("tell me").stream(chunks::add);

        assertThat(chunks).containsExactly("First sentence is fine. ", "[Response blocked by guardrail: no-secrets]");
    }

    @Test @DisplayName("long text with no sentence end is released at a word break, not held forever")
    void longTextIsReleased() throws Exception {
        String word = "lorem ";
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 200; i++) tokens.add(word);
        var app = CafeAI.create();
        app.ai(new Tokens(tokens));
        app.guard(new Rail("never", GuardRail.Position.POST_LLM, GuardRail.Action.BLOCK, "nothing-matches-this"));

        List<String> events = StreamedNewlinesTest.sseEvents(sse(app));
        assertThat(events.size()).isGreaterThan(2);
        assertThat(events.subList(0, events.size() - 1)).allSatisfy(e -> assertThat(e.length()).isLessThanOrEqualTo(406));
        assertThat(String.join("", events.subList(0, events.size() - 1))).isEqualTo(word.repeat(200));
    }
}
