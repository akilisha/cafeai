package io.cafeai.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.LangchainBridge;
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

/**
 * Tokens with line breaks in them -- paragraphs, list items, a lone newline -- reach an
 * SSE client whole. Each token was once written as a single {@code data:} field, so an
 * SSE client kept only the text before the first break.
 */
@DisplayName("res.stream(...) keeps line breaks inside tokens")
class StreamedNewlinesTest {

    static final List<String> TOKENS = List.of(
            "Para one.\n\nPara two", "\n", "- first\r\n- second", "no breaks", "trailing\n");

    record Lines() implements AiProvider, LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()    { return "lines"; }
        @Override public String       modelId() { return "lines-1"; }
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

    @Test @DisplayName("an SSE client reassembles every token exactly as the model sent it")
    void tokensArriveWhole() throws Exception {
        var app = CafeAI.create();
        app.ai(new Lines());
        app.get("/sse", (req, res, next) -> res.stream(app.prompt("write")));
        var latch = new CountDownLatch(1);
        app.listen(0, latch::countDown);
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            String body = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/sse")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();

            // SSE carries line breaks, not which kind: a client rejoins lines with LF, so a
            // token's CRLF arrives as LF. Everything else arrives exactly as sent.
            List<String> expected = new ArrayList<>(TOKENS.stream().map(t -> t.replace("\r\n", "\n")).toList());
            expected.add("[DONE]");
            assertThat(sseEvents(body)).containsExactlyElementsOf(expected);
        } finally {
            app.stop();
        }
    }

    /**
     * The data of each event, decoded as the SSE specification (and a browser's
     * EventSource) does: lines end at CR, LF or CRLF; a blank line ends an event; a
     * {@code data:} field drops one leading space; an event's data lines join with LF.
     */
    static List<String> sseEvents(String stream) {
        List<String> events = new ArrayList<>();
        List<String> data = new ArrayList<>();
        for (String line : stream.split("\r\n|\r|\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) events.add(String.join("\n", data));
                data.clear();
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                data.add(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        return events;
    }
}
