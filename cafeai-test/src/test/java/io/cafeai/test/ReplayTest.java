package io.cafeai.test;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Replay")
class ReplayTest {

    @TempDir Path dir;

    /** A model that numbers its answers, so a replay is told apart from a fresh call. */
    static final class Counting implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        final AtomicInteger calls = new AtomicInteger();
        private final Double temperature;

        Counting() { this(null); }
        Counting(Double temperature) { this.temperature = temperature; }

        @Override public String       name()        { return "counting"; }
        @Override public String       modelId()     { return "count-1"; }
        @Override public ProviderType type()        { return ProviderType.CUSTOM; }
        @Override public Double       temperature() { return temperature; }
        @Override public AiProvider   withTemperature(double t) { return new Counting(t); }

        ChatResponse answer() {
            int n = calls.incrementAndGet();
            return ChatResponse.builder().aiMessage(AiMessage.from("answer " + n))
                    .tokenUsage(new TokenUsage(10, n, 10 + n)).finishReason(FinishReason.STOP)
                    .modelName("count-1").build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) { return answer(); }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    ChatResponse response = answer();
                    h.onPartialThinking(new PartialThinking("let me think"));
                    h.onPartialResponse("answer ");
                    h.onPartialResponse(String.valueOf(calls.get()));
                    h.onCompleteResponse(response);
                }
            };
        }
    }

    /** Same name and model as {@link Counting}, but cannot be built -- like a provider with no API key. */
    static final class NoKey implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()    { return "counting"; }
        @Override public String       modelId() { return "count-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }
        @Override public ChatModel toChatModel() { throw new IllegalStateException("Missing API key"); }
        @Override public StreamingChatModel toStreamingChatModel() { throw new IllegalStateException("Missing API key"); }
    }

    private static ChatRequest ask(String text) {
        return ChatRequest.builder().messages(UserMessage.from(text)).build();
    }

    private static long cassetteCount(Path dir) throws Exception {
        if (!Files.exists(dir)) return 0;
        try (var files = Files.list(dir)) { return files.filter(f -> f.toString().endsWith(".json")).count(); }
    }

    @Test @DisplayName("auto: the first call reaches the model and is recorded; the same call again is replayed")
    void autoRecordsThenReplays() throws Exception {
        var model = new Counting();
        ChatModel replay = Replay.of(model, dir).mode(ReplayMode.AUTO).toChatModel();

        ChatResponse first = replay.chat(ask("hello"));
        ChatResponse second = replay.chat(ask("hello"));

        assertThat(model.calls).hasValue(1);
        assertThat(cassetteCount(dir)).isEqualTo(1);
        assertThat(second.aiMessage().text()).isEqualTo(first.aiMessage().text()).isEqualTo("answer 1");
        assertThat(second.tokenUsage().outputTokenCount()).isEqualTo(1);
        assertThat(second.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(second.modelName()).isEqualTo("count-1");
    }

    @Test @DisplayName("replay: a call with no recording fails, naming it, and never reaches the model")
    void replayMissFails() {
        var model = new Counting();
        ChatModel replay = Replay.of(model, dir).mode(ReplayMode.REPLAY).toChatModel();

        assertThatThrownBy(() -> replay.chat(ask("never recorded")))
                .isInstanceOf(ReplayMissException.class)
                .hasMessageContaining("never recorded")
                .hasMessageContaining("cafeai.replay.mode=auto");
        assertThat(model.calls).hasValue(0);
    }

    @Test @DisplayName("replay works where the real provider cannot even be built (no API key in CI)")
    void replayNeedsNoKey() throws Exception {
        Replay.of(new Counting(), dir).mode(ReplayMode.AUTO).toChatModel().chat(ask("hello"));

        ChatModel ci = Replay.of(new NoKey(), dir).mode(ReplayMode.REPLAY).toChatModel();
        assertThat(ci.chat(ask("hello")).aiMessage().text()).isEqualTo("answer 1");

        // ...and a miss in that setting is still a ReplayMissException, not the key error.
        assertThatThrownBy(() -> ci.chat(ask("something else"))).isInstanceOf(ReplayMissException.class);
    }

    @Test @DisplayName("record: every call reaches the model and overwrites its recording")
    void recordOverwrites() throws Exception {
        var model = new Counting();
        Replay.of(model, dir).mode(ReplayMode.AUTO).toChatModel().chat(ask("hello"));
        ChatResponse rerecorded = Replay.of(model, dir).mode(ReplayMode.RECORD).toChatModel().chat(ask("hello"));

        assertThat(model.calls).hasValue(2);
        assertThat(rerecorded.aiMessage().text()).isEqualTo("answer 2");
        assertThat(cassetteCount(dir)).isEqualTo(1);
        assertThat(Replay.of(model, dir).mode(ReplayMode.REPLAY).toChatModel().chat(ask("hello")).aiMessage().text())
                .isEqualTo("answer 2");
    }

    @Test @DisplayName("a different prompt, or a different temperature, is a different recording")
    void whatDecidesTheAnswerIsInTheKey() throws Exception {
        var model = new Counting();
        Replay replay = Replay.of(model, dir).mode(ReplayMode.AUTO);

        replay.toChatModel().chat(ask("hello"));
        replay.toChatModel().chat(ask("goodbye"));
        ((Replay) replay.withTemperature(0.0)).toChatModel().chat(ask("hello"));

        assertThat(model.calls).hasValue(1 + 1);   // the temperature variant is a new Counting
        assertThat(cassetteCount(dir)).isEqualTo(3);
    }

    @Test @DisplayName("normalize: a value that changes every run can be blanked so the recording still matches")
    void normalizeStripsVolatileValues() {
        var model = new Counting();
        Replay replay = Replay.of(model, dir).mode(ReplayMode.AUTO)
                .normalize(s -> s.replaceAll("at \\d\\d:\\d\\d", "at <time>"));

        replay.toChatModel().chat(ask("what happened at 09:15"));
        String replayed = replay.toChatModel().chat(ask("what happened at 17:42")).aiMessage().text();

        assertThat(model.calls).hasValue(1);
        assertThat(replayed).isEqualTo("answer 1");
    }

    @Test @DisplayName("a streamed call is replayed as the same stream, thinking included")
    void streamsReplayAsStreams() throws Exception {
        var model = new Counting();
        StreamingChatModel replay = Replay.of(model, dir).mode(ReplayMode.AUTO).toStreamingChatModel();

        List<String> recorded = stream(replay, ask("hello"));
        List<String> replayed = stream(replay, ask("hello"));

        assertThat(model.calls).hasValue(1);
        assertThat(replayed).isEqualTo(recorded)
                .containsExactly("thinking:let me think", "text:answer ", "text:1", "done:answer 1");
    }

    @Test @DisplayName("a streamed replay miss is reported through onError")
    void streamedMissIsAnError() throws Exception {
        StreamingChatModel replay = Replay.of(new NoKey(), dir).mode(ReplayMode.REPLAY).toStreamingChatModel();
        assertThat(stream(replay, ask("hello"))).singleElement().asString().startsWith("error:ReplayMissException");
    }

    @Test @DisplayName("through CafeAI: app.prompt(...).call() records once and replays after")
    void throughTheApp() {
        var model = new Counting();
        var app = CafeAI.create();
        app.ai(Replay.of(model, dir).mode(ReplayMode.AUTO));

        String first = app.prompt("hello").call().text();
        String second = app.prompt("hello").call().text();

        assertThat(model.calls).hasValue(1);
        assertThat(second).isEqualTo(first).isEqualTo("answer 1");
    }

    @Test @DisplayName("through CafeAI: app.prompt(...).stream(...) replays the recorded tokens")
    void streamingThroughTheApp() {
        var model = new Counting();
        var app = CafeAI.create();
        app.ai(Replay.of(model, dir).mode(ReplayMode.AUTO));

        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        app.prompt("hello").stream(first::add);
        app.prompt("hello").stream(second::add);

        assertThat(model.calls).hasValue(1);
        assertThat(second).isEqualTo(first).containsExactly("answer ", "1");
    }

    @Test @DisplayName("the mode can come from cafeai.replay.mode, and a bad value is named")
    void modeFromConfig() {
        System.setProperty("cafeai.replay.mode", "replay");
        try {
            assertThat(Replay.of(new Counting(), dir).currentMode()).isEqualTo(ReplayMode.REPLAY);
            System.setProperty("cafeai.replay.mode", "sometimes");
            assertThatThrownBy(() -> Replay.of(new Counting(), dir).currentMode())
                    .hasMessageContaining("auto, replay or record");
        } finally {
            System.clearProperty("cafeai.replay.mode");
        }
    }

    private static List<String> stream(StreamingChatModel model, ChatRequest request) throws Exception {
        List<String> events = new ArrayList<>();
        var done = new java.util.concurrent.CountDownLatch(1);
        model.chat(request, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String text) { events.add("text:" + text); }
            @Override public void onPartialThinking(PartialThinking t) { events.add("thinking:" + t.text()); }
            @Override public void onCompleteResponse(ChatResponse r) { events.add("done:" + r.aiMessage().text()); done.countDown(); }
            @Override public void onError(Throwable e) { events.add("error:" + e.getClass().getSimpleName()); done.countDown(); }
        });
        assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return events;
    }
}
