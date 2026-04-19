package io.cafeai.core.ai;

import io.cafeai.core.CafeAI;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinates text generation and speech synthesis by splitting a prompt
 * response into sentence-length chunks and synthesising each chunk in sequence.
 *
 * <p>This produces a more natural voice experience than synthesising the entire
 * response at once — the first sentence is available as audio while the rest
 * of the response is still being assembled.
 *
 * <p>Note: This implementation uses synchronous chunking. True token-by-token
 * streaming (feeding partial tokens to TTS as they arrive) requires
 * {@code app.prompt().stream()} which is not yet implemented. This will be
 * upgraded when streaming is available in a future roadmap item.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   var pipeline = StreamingVoicePipeline.create(app)
 *       .textProvider("tutor")
 *       .voiceProvider("voice");
 *
 *   // Stream synthesised audio chunks
 *   pipeline.stream("Explain standard deviation", chunk -> {
 *       audioOutput.play(chunk.audioBytes());
 *       System.out.print(chunk.text());
 *   });
 *
 *   // Stream audio bytes only
 *   pipeline.streamAudio("Welcome to today's lesson", audioOutput::play);
 * }</pre>
 *
 * <h2>Chunking behaviour</h2>
 * <p>Text is split at sentence boundaries (period, question mark, exclamation mark,
 * followed by whitespace or end of string). Short chunks below {@link #minChunkSize}
 * are merged with the next sentence to avoid synthesising fragments too short
 * for natural-sounding speech.
 *
 * @see SynthesisRequest
 * @see SynthesisResponse
 */
public final class StreamingVoicePipeline {

    /** Default minimum characters per chunk before synthesis. */
    public static final int DEFAULT_MIN_CHUNK_SIZE = 40;

    private final CafeAI app;
    private String textProviderName;
    private String voiceProviderName;
    private int    minChunkSize = DEFAULT_MIN_CHUNK_SIZE;

    private StreamingVoicePipeline(CafeAI app) {
        this.app = app;
    }

    /**
     * Creates a new pipeline attached to the given app.
     *
     * <pre>{@code
     *   var pipeline = StreamingVoicePipeline.create(app)
     *       .textProvider("tutor")
     *       .voiceProvider("voice");
     * }</pre>
     */
    public static StreamingVoicePipeline create(CafeAI app) {
        if (app == null) throw new IllegalArgumentException("app must not be null");
        return new StreamingVoicePipeline(app);
    }

    /**
     * Sets the named provider to use for text generation.
     * If not set, the default provider is used.
     */
    public StreamingVoicePipeline textProvider(String providerName) {
        this.textProviderName = providerName;
        return this;
    }

    /**
     * Sets the named provider to use for TTS synthesis.
     * If not set, the default provider is used.
     */
    public StreamingVoicePipeline voiceProvider(String providerName) {
        this.voiceProviderName = providerName;
        return this;
    }

    /**
     * Sets the minimum chunk size in characters before synthesis is triggered.
     * Smaller values produce more audio chunks with lower latency per chunk;
     * larger values produce fewer, more natural-sounding synthesis calls.
     *
     * <p>Default: {@value #DEFAULT_MIN_CHUNK_SIZE} characters.
     */
    public StreamingVoicePipeline minChunkSize(int size) {
        if (size < 1) throw new IllegalArgumentException("minChunkSize must be >= 1");
        this.minChunkSize = size;
        return this;
    }

    /**
     * Generates text for the given prompt, splits it into sentence chunks,
     * synthesises each chunk, and delivers {@link VoiceChunk} objects to the consumer.
     *
     * <p>Each chunk carries both the text fragment and the corresponding audio bytes,
     * allowing the caller to display text and play audio simultaneously.
     *
     * @param prompt   the text prompt to send to the LLM
     * @param onChunk  called for each synthesised chunk, in order
     */
    public void stream(String prompt, Consumer<VoiceChunk> onChunk) {
        if (prompt == null || prompt.isBlank())
            throw new IllegalArgumentException("prompt must not be null or blank");
        if (onChunk == null)
            throw new IllegalArgumentException("onChunk consumer must not be null");

        // -- 1. Generate the full text response ----------------------------------
        var promptReq = app.prompt(prompt);
        if (textProviderName != null) promptReq = promptReq.provider(textProviderName);
        String fullText = promptReq.call().text();

        // -- 2. Split into sentence chunks ---------------------------------------
        List<String> chunks = splitIntoChunks(fullText, minChunkSize);

        // -- 3. Synthesise each chunk and deliver --------------------------------
        for (String chunk : chunks) {
            var synthReq = app.synthesise(chunk);
            if (voiceProviderName != null) synthReq = synthReq.provider(voiceProviderName);
            SynthesisResponse audio = synthReq.call();
            onChunk.accept(new VoiceChunk(chunk, audio.audioBytes(), audio.format()));
        }
    }

    /**
     * Generates text for the given prompt, splits it into sentence chunks,
     * synthesises each chunk, and delivers the raw audio bytes to the consumer.
     *
     * <p>Use this when you only need audio and don't need the text fragments.
     *
     * @param prompt       the text prompt to send to the LLM
     * @param onAudioChunk called for each synthesised audio byte array, in order
     */
    public void streamAudio(String prompt, Consumer<byte[]> onAudioChunk) {
        stream(prompt, chunk -> onAudioChunk.accept(chunk.audioBytes()));
    }

    /**
     * Splits text at sentence boundaries, merging short fragments with the
     * following sentence until {@code minSize} is reached.
     *
     * <p>Sentence boundaries: {@code .}, {@code ?}, {@code !} followed by
     * whitespace or end of string.
     */
    static List<String> splitIntoChunks(String text, int minSize) {
        if (text == null || text.isBlank()) return List.of();

        List<String> sentences = new ArrayList<>();
        StringBuilder current  = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);

            boolean isBoundary = (c == '.' || c == '?' || c == '!')
                && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)));

            if (isBoundary) {
                // Skip over trailing whitespace after the punctuation
                while (i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                    i++;
                }
                sentences.add(current.toString().trim());
                current.setLength(0);
            }
        }

        // Capture any trailing text without terminal punctuation
        if (!current.isEmpty()) {
            sentences.add(current.toString().trim());
        }

        // Merge short sentences forward
        List<String> chunks = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String sentence : sentences) {
            if (!pending.isEmpty()) pending.append(' ');
            pending.append(sentence);
            if (pending.length() >= minSize) {
                chunks.add(pending.toString());
                pending.setLength(0);
            }
        }
        if (!pending.isEmpty()) {
            chunks.add(pending.toString());
        }

        return chunks.isEmpty() ? List.of(text.trim()) : chunks;
    }

    /**
     * A synthesised voice chunk — pairs the text fragment with its audio bytes.
     *
     * @param text       the text fragment that was synthesised
     * @param audioBytes the raw audio bytes for this fragment
     * @param format     the audio format (e.g. "mp3", "wav")
     */
    public record VoiceChunk(String text, byte[] audioBytes, String format) {
        /** Whether audio bytes are present and non-empty. */
        public boolean hasAudio() { return audioBytes != null && audioBytes.length > 0; }
    }
}
