package io.cafeai.test;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.AiProvider.ProviderType;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.internal.LangchainBridge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Records a provider's model calls to files and replays them, so tests run without
 * an API key, at no cost, and get the same answer every run.
 *
 * <pre>{@code
 *   app.ai(Replay.of(OpenAI.of("gpt-4o-mini"), Path.of("src/test/resources/cassettes")));
 * }</pre>
 *
 * <p>{@code Replay} is itself an {@link AiProvider}, so everything that uses a provider
 * goes through it unchanged: {@code app.prompt(...)} calls and streams, vision,
 * history summaries, and agents from {@code cafeai-aiservices} and
 * {@code cafeai-agentic}. Calls CafeAI makes over plain HTTP — Whisper transcription
 * and text-to-speech — are not recorded.
 *
 * <p>The mode comes from {@code cafeai.replay.mode} ({@code auto}, {@code replay} or
 * {@code record}; default {@code auto}) unless {@link #mode(ReplayMode)} sets it — so
 * CI can run with {@code -Dcafeai.replay.mode=replay} and never reach a real model.
 * See {@link ReplayMode}.
 *
 * <p>A call matches a recording when everything that decides its answer is the same
 * (see {@code Cassettes}). If prompts carry values that change every run — a
 * timestamp, an id — strip them with {@link #normalize(UnaryOperator)}, or no
 * recording will ever match.
 */
public final class Replay implements AiProvider,
        LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {

    /** {@code auto}, {@code replay} or {@code record}. */
    public static final ConfigKey<String> MODE = ConfigKey.of(
        "cafeai.replay.mode", String.class, "auto",
        "What Replay does with a model call: auto (replay, or record when missing), replay (fail when missing), record (always re-record).");

    private final AiProvider delegate;
    private final Path dir;
    private final ReplayMode mode;     // null: read cafeai.replay.mode when used
    private final UnaryOperator<String> normalizer;
    private final Cassettes cassettes;
    private volatile ChatModel chatModel;
    private volatile StreamingChatModel streamingModel;

    private Replay(AiProvider delegate, Path dir, ReplayMode mode, UnaryOperator<String> normalizer) {
        this.delegate = Objects.requireNonNull(delegate, "provider");
        this.dir = Objects.requireNonNull(dir, "cassette directory");
        this.mode = mode;
        this.normalizer = normalizer;
        this.cassettes = new Cassettes(dir, delegate, normalizer);
    }

    /** Wraps {@code provider}, keeping its recordings in {@code cassettes}, one file per call. */
    public static Replay of(AiProvider provider, Path cassettes) {
        return new Replay(provider, cassettes, null, UnaryOperator.identity());
    }

    /** The same, with a fixed mode instead of {@code cafeai.replay.mode}. */
    public Replay mode(ReplayMode mode) {
        return new Replay(delegate, dir, Objects.requireNonNull(mode, "mode"), normalizer);
    }

    /**
     * The same, rewriting each request's description before it is hashed — to blank
     * out values that change every run, e.g.
     * {@code s -> s.replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z?", "<time>")}.
     * Applied after any normaliser already set.
     */
    public Replay normalize(UnaryOperator<String> normalizer) {
        Objects.requireNonNull(normalizer, "normalizer");
        UnaryOperator<String> before = this.normalizer;
        return new Replay(delegate, dir, mode, s -> normalizer.apply(before.apply(s)));
    }

    /**
     * The mode in effect: the one set with {@link #mode}, else {@code cafeai.replay.mode}
     * from the app's configuration, else the {@code cafeai.replay.mode} system property
     * or {@code CAFEAI_REPLAY_MODE} environment variable, else {@code auto}.
     *
     * <p>The property and variable are read here even without {@code cafeai-config}:
     * this is a safety switch, and {@code -Dcafeai.replay.mode=replay} in CI must hold
     * whether or not the app happens to include that module.
     */
    public ReplayMode currentMode() {
        if (mode != null) return mode;
        String raw = AppConfig.load().getRaw(MODE)
                .or(() -> Optional.ofNullable(System.getProperty(MODE.name())))
                .or(() -> Optional.ofNullable(System.getenv("CAFEAI_REPLAY_MODE")))
                .orElse(MODE.defaultValue());
        String value = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return ReplayMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cafeai.replay.mode must be auto, replay or record, not '"
                    + value.toLowerCase(Locale.ROOT) + "'", e);
        }
    }

    // -- AiProvider: everything is the wrapped provider's ------------------------------

    @Override public String       name()           { return delegate.name(); }
    @Override public String       modelId()        { return delegate.modelId(); }
    @Override public ProviderType type()           { return delegate.type(); }
    @Override public Double       temperature()    { return delegate.temperature(); }
    @Override public Integer      maxTokens()      { return delegate.maxTokens(); }
    @Override public Duration     timeout()        { return delegate.timeout(); }
    @Override public boolean      supportsVision() { return delegate.supportsVision(); }
    @Override public boolean      supportsAudio()  { return delegate.supportsAudio(); }
    @Override public boolean      supportsTts()    { return delegate.supportsTts(); }

    @Override public AiProvider withTemperature(double temperature) {
        return new Replay(delegate.withTemperature(temperature), dir, mode, normalizer);
    }

    @Override public AiProvider withMaxTokens(int maxTokens) {
        return new Replay(delegate.withMaxTokens(maxTokens), dir, mode, normalizer);
    }

    @Override public AiProvider withTimeout(Duration timeout) {
        return new Replay(delegate.withTimeout(timeout), dir, mode, normalizer);
    }

    @Override public String toString() {
        return "Replay[" + delegate.name() + "/" + delegate.modelId() + " -> " + dir + "]";
    }

    // -- the models CafeAI calls --------------------------------------------------------

    @Override
    public ChatModel toChatModel() {
        ChatModel model = chatModel;
        if (model == null) chatModel = model = new RecordingChatModel(lazy(() -> LangchainBridge.chatModel(delegate)));
        return model;
    }

    @Override
    public StreamingChatModel toStreamingChatModel() {
        StreamingChatModel model = streamingModel;
        if (model == null) streamingModel = model = new RecordingStreamingModel(lazy(() -> LangchainBridge.streamingChatModel(delegate)));
        return model;
    }

    /**
     * The real model is built only when a call has to reach it: building one needs the
     * provider's API key, and replaying must work where there is none (CI).
     *
     * <p>For the same reason the recording models report no default parameters and no
     * capabilities of their own — asking the real model would build it, and answers that
     * differed between a machine with a key and one without would change the requests,
     * so recordings would not match. One visible effect: agents built on
     * {@code AiServices} do not use a provider's native JSON-schema output through
     * {@code Replay}; they ask for structured output in the prompt instead.
     */
    private static <T> Supplier<T> lazy(Supplier<T> factory) {
        return new Supplier<>() {
            private volatile T value;
            @Override public T get() {
                T v = value;
                if (v == null) {
                    synchronized (this) {
                        v = value;
                        if (v == null) value = v = factory.get();
                    }
                }
                return v;
            }
        };
    }

    private final class RecordingChatModel implements ChatModel {
        private final Supplier<ChatModel> real;

        RecordingChatModel(Supplier<ChatModel> real) { this.real = real; }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            ReplayMode m = currentMode();
            String key = cassettes.key(request);
            if (m != ReplayMode.RECORD) {
                Optional<Cassettes.Recording> recorded = cassettes.read(key);
                if (recorded.isPresent()) return recorded.get().response();
                if (m == ReplayMode.REPLAY) throw new ReplayMissException(key, Cassettes.lastUserText(request));
            }
            ChatResponse response = real.get().chat(request);
            cassettes.write(key, request, response, null);
            return response;
        }
    }

    private final class RecordingStreamingModel implements StreamingChatModel {
        private final Supplier<StreamingChatModel> real;

        RecordingStreamingModel(Supplier<StreamingChatModel> real) { this.real = real; }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            ReplayMode m = currentMode();
            String key;
            Optional<Cassettes.Recording> recorded;
            try {
                key = cassettes.key(request);
                recorded = m == ReplayMode.RECORD ? Optional.empty() : cassettes.read(key);
            } catch (RuntimeException e) {
                handler.onError(e);
                return;
            }
            if (recorded.isPresent()) {
                replay(recorded.get(), handler);
                return;
            }
            if (m == ReplayMode.REPLAY) {
                handler.onError(new ReplayMissException(key, Cassettes.lastUserText(request)));
                return;
            }
            List<Cassettes.Chunk> chunks = new ArrayList<>();
            StreamingChatModel model;
            try {
                model = real.get();
            } catch (RuntimeException e) {
                handler.onError(e);
                return;
            }
            model.chat(request, new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String text) {
                    chunks.add(new Cassettes.Chunk(text, false));
                    handler.onPartialResponse(text);
                }
                @Override public void onPartialThinking(PartialThinking thinking) {
                    chunks.add(new Cassettes.Chunk(thinking.text(), true));
                    handler.onPartialThinking(thinking);
                }
                @Override public void onCompleteResponse(ChatResponse response) {
                    try {
                        cassettes.write(key, request, response, chunks);
                    } catch (RuntimeException e) {
                        handler.onError(e);
                        return;
                    }
                    handler.onCompleteResponse(response);
                }
                @Override public void onError(Throwable error) {
                    handler.onError(error);
                }
            });
        }

        /** Replays a recording as a stream; a recording made without streaming arrives as one chunk. */
        private static void replay(Cassettes.Recording recording, StreamingChatResponseHandler handler) {
            if (recording.chunks().isEmpty()) {
                String text = recording.response().aiMessage().text();
                if (text != null && !text.isEmpty()) handler.onPartialResponse(text);
            } else {
                for (Cassettes.Chunk c : recording.chunks()) {
                    if (c.thinking()) handler.onPartialThinking(new PartialThinking(c.text()));
                    else handler.onPartialResponse(c.text());
                }
            }
            handler.onCompleteResponse(recording.response());
        }
    }
}
