package io.cafeai.core.ai;

/**
 * Factory for OpenAI LLM providers.
 *
 * <pre>{@code
 *   app.ai(OpenAI.gpt4o());
 *   app.ai(OpenAI.gpt4oMini());   // cheaper, faster -- good for simple queries
 *   app.ai(OpenAI.o1());          // reasoning model
 *   app.ai(OpenAI.of("gpt-4-turbo")); // any model by ID
 * }</pre>
 */
public final class OpenAI {

    private OpenAI() {}

    public static AiProvider gpt4o()        { return of("gpt-4o"); }
    public static AiProvider gpt4oMini()    { return of("gpt-4o-mini"); }
    public static AiProvider o1()           { return of("o1"); }
    public static AiProvider o1Mini()       { return of("o1-mini"); }

    /**
     * OpenAI Whisper -- dedicated speech transcription model.
     *
     * <p>Supports audio/wav, audio/mp3, audio/ogg, audio/m4a, audio/flac.
     *
     * <p><strong>Note:</strong> Whisper uses OpenAI's {@code /v1/audio/transcriptions}
     * endpoint, not the chat completions API. LangChain4j does not currently wrap
     * the Whisper endpoint. {@code app.audio()} with this provider will route through
     * the chat completions path using {@code gpt-4o-audio-preview} internally until
     * LangChain4j adds Whisper endpoint support.
     *
     * <pre>{@code
     *   app.ai(OpenAI.whisper());
     *   AudioResponse r = app.audio(
     *       "Transcribe this call.", audioBytes, "audio/wav").call();
     * }</pre>
     */
    public static AiProvider whisper() {
        return new OpenAiAudioProvider("whisper-1");
    }

    /** Any OpenAI model by its model ID string. */
    public static AiProvider of(String modelId) {
        return new OpenAiProvider(modelId);
    }

    private record OpenAiProvider(String modelId) implements AiProvider {
        // Vision-capable models -- gpt-4o and variants support image/PDF input.
        // gpt-4o-mini does not support vision.
        private static final java.util.Set<String> VISION_MODELS = java.util.Set.of(
            "gpt-4o", "gpt-4o-2024-11-20", "gpt-4o-2024-08-06", "gpt-4o-2024-05-13",
            "gpt-4-turbo", "gpt-4-turbo-2024-04-09", "gpt-4-vision-preview"
        );
        // Audio-capable models -- gpt-4o supports audio input natively.
        private static final java.util.Set<String> AUDIO_MODELS = java.util.Set.of(
            "gpt-4o", "gpt-4o-2024-11-20", "gpt-4o-2024-08-06",
            "gpt-4o-audio-preview", "gpt-4o-audio-preview-2024-10-01"
        );
        @Override public String       name()          { return "openai"; }
        @Override public ProviderType type()          { return ProviderType.OPENAI; }
        @Override public boolean      supportsVision() {
            return VISION_MODELS.contains(modelId);
        }
        @Override public boolean      supportsAudio() {
            return AUDIO_MODELS.contains(modelId);
        }
    }

    /**
     * Dedicated audio provider record for Whisper and future audio-specific models.
     * Declared separately so it can override supportsAudio() cleanly without
     * polluting the general OpenAiProvider model ID set.
     */
    private record OpenAiAudioProvider(String modelId) implements AiProvider,
            io.cafeai.core.internal.LangchainBridge.ChatModelAccess {
        @Override public String       name()          { return "openai"; }
        @Override public ProviderType type()          { return ProviderType.OPENAI; }
        @Override public boolean      supportsAudio() { return true; }
        @Override public boolean      supportsVision() { return false; }

        @Override
        public dev.langchain4j.model.chat.ChatModel toChatModel() {
            // Whisper endpoint is not yet supported via LangChain4j chat completions.
            // Route through gpt-4o-audio-preview which does support audio via chat API.
            // Replace this when LangChain4j adds WhisperTranscriptionModel support.
            return dev.langchain4j.model.openai.OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-audio-preview")
                .build();
        }
    }
}
