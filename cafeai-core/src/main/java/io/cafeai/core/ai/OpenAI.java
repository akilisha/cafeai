package io.cafeai.core.ai;

/**
 * Factory for OpenAI LLM providers.
 *
 * <p>Chat model ids are provider data — they change and get retired — so CafeAI
 * does not ship named constants for them. Pass the id you want; OpenAI's API is
 * the source of truth, and a wrong id surfaces as a clean provider error rather
 * than a stale default.
 *
 * <pre>{@code
 *   app.ai(OpenAI.of("gpt-4o"));
 *   app.ai(OpenAI.of("gpt-4o-mini"));   // cheaper, faster
 *   app.ai("voice", OpenAI.tts());       // text-to-speech (a distinct endpoint, not a chat model)
 *   app.ai(OpenAI.whisper());            // speech transcription
 * }</pre>
 */
public final class OpenAI {

    private OpenAI() {}

    /** An OpenAI provider for the given chat model id (e.g. {@code "gpt-4o"}). */
    public static AiProvider of(String modelId) {
        return new OpenAiProvider(modelId);
    }

    /**
     * OpenAI TTS — text-to-speech synthesis using the default voice (alloy) and
     * format (mp3). This is the {@code /v1/audio/speech} endpoint with a fixed
     * model, not a chat-model choice, so it stays a named factory.
     *
     * <pre>{@code
     *   app.ai("voice", OpenAI.tts());
     *   byte[] audio = app.synthesise("Hello, welcome.").provider("voice").call().audioBytes();
     * }</pre>
     */
    public static AiProvider tts() {
        return tts("alloy", "mp3");
    }

    /**
     * OpenAI TTS with a specific voice and format.
     *
     * <p>Voices: {@code alloy}, {@code echo}, {@code fable}, {@code onyx},
     * {@code nova}, {@code shimmer}. Formats: {@code mp3}, {@code opus},
     * {@code aac}, {@code flac}, {@code wav}, {@code pcm}.
     */
    public static AiProvider tts(String voice, String format) {
        return new OpenAiTtsProvider(voice, format);
    }

    /**
     * OpenAI Whisper — speech transcription. A distinct endpoint with a fixed
     * model, so it stays a named factory.
     *
     * <p><strong>Note:</strong> LangChain4j does not wrap the
     * {@code /v1/audio/transcriptions} endpoint; {@code app.audio()} with this
     * provider routes through the chat-completions path using an audio-capable
     * model internally.
     */
    public static AiProvider whisper() {
        return new OpenAiAudioProvider("whisper-1");
    }

    private record OpenAiProvider(String modelId) implements AiProvider {
        @Override public String       name()          { return "openai"; }
        @Override public ProviderType type()          { return ProviderType.OPENAI; }
        // Modern OpenAI chat models are broadly multimodal; let the API reject the exception.
        @Override public boolean      supportsVision() { return true; }
    }

    /**
     * Dedicated audio provider record for Whisper and future audio-specific models.
     */
    private record OpenAiAudioProvider(String modelId) implements AiProvider,
            io.cafeai.core.internal.LangchainBridge.ChatModelAccess {
        @Override public String       name()          { return "openai"; }
        @Override public ProviderType type()          { return ProviderType.OPENAI; }
        @Override public boolean      supportsAudio() { return true; }
        @Override public boolean      supportsVision() { return false; }

        @Override
        public dev.langchain4j.model.chat.ChatModel toChatModel() {
            String resolvedModel = (modelId() == null || modelId().equals("whisper-1"))
                ? "gpt-4o-audio-preview"
                : modelId();
            return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName(resolvedModel)
                .build();
        }
    }

    /**
     * TTS provider record — uses OpenAI /v1/audio/speech endpoint directly.
     * Synthesis is performed in CafeAIApp.executeSynthesis() via java.net.http.
     */
    public record OpenAiTtsProvider(String voice, String format) implements AiProvider {
        @Override public String       name()        { return "openai"; }
        @Override public String       modelId()     { return "tts-1"; }
        @Override public ProviderType type()        { return ProviderType.OPENAI; }
        @Override public boolean      supportsTts() { return true; }
    }
}
