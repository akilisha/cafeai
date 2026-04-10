package io.cafeai.core.internal;

import dev.langchain4j.data.message.*;
import io.cafeai.core.ai.AudioRequest;
import io.cafeai.core.ai.AiProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Handles audio calls for CafeAI.
 *
 * <h2>Provider routing</h2>
 * <ul>
 *   <li><strong>OpenAI</strong> — LangChain4j does not support {@code AudioContent}
 *       for OpenAI's chat completions API (only {@code text} and {@code image_url}
 *       content types are accepted). Audio is routed through OpenAI's
 *       {@code /v1/audio/transcriptions} endpoint directly via {@code java.net.http}
 *       (multipart/form-data). The transcript is then optionally sent back through
 *       {@code app.prompt()} for further reasoning.</li>
 *   <li><strong>Gemini</strong> — {@code AudioContent} is supported natively
 *       via the chat completions path.</li>
 * </ul>
 *
 * <p>Package-private — internal to {@code CafeAIApp}.
 */
final class AudioMessageBuilder {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
        "audio/wav", "audio/wave",
        "audio/mp3", "audio/mpeg",
        "audio/ogg",
        "audio/m4a",
        "audio/flac"
    );

    private static final String WHISPER_URL =
        "https://api.openai.com/v1/audio/transcriptions";

    private AudioMessageBuilder() {}

    /**
     * Returns true if this provider requires the direct Whisper endpoint
     * rather than chat completions. True for all OpenAI providers.
     */
    static boolean requiresWhisperEndpoint(AiProvider provider) {
        return provider.type() == AiProvider.ProviderType.OPENAI;
    }

    /**
     * Calls OpenAI's /v1/audio/transcriptions endpoint directly.
     * Returns the raw transcript text.
     *
     * @throws AudioRequest.UnsupportedAudioFormatException for unsupported MIME types
     * @throws RuntimeException if the HTTP call fails
     */
    static String transcribeViaWhisper(byte[] audioBytes, String mimeType,
                                        String prompt) {
        validateMimeType(mimeType);

        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "OPENAI_API_KEY environment variable is not set.");
        }

        String boundary  = "----CafeAIBoundary" + System.nanoTime();
        String extension = extensionFor(mimeType);

        // Build multipart/form-data body
        byte[] body = buildMultipart(boundary, audioBytes, extension, mimeType, prompt);

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WHISPER_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

            HttpResponse<String> response =
                client.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                    "OpenAI Whisper API error " + response.statusCode() +
                    ": " + response.body());
            }

            // Parse {"text": "..."} — avoid pulling in a JSON library
            String json = response.body();
            int start = json.indexOf("\"text\"");
            if (start < 0) throw new RuntimeException(
                "Unexpected Whisper response: " + json);
            int colon = json.indexOf(':', start);
            int quote1 = json.indexOf('"', colon + 1);
            int quote2 = json.indexOf('"', quote1 + 1);
            return json.substring(quote1 + 1, quote2);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Audio transcription failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds LangChain4j messages for providers that support AudioContent
     * natively (e.g. Gemini).
     */
    static List<ChatMessage> buildForGemini(String prompt, byte[] content,
                                             String mimeType, String systemPrompt,
                                             List<ChatMessage> history) {
        validateMimeType(mimeType);
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank())
            messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(history);
        String base64 = Base64.getEncoder().encodeToString(content);
        messages.add(UserMessage.from(
            AudioContent.from(base64, normalise(mimeType)),
            TextContent.from(prompt)));
        return messages;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void validateMimeType(String mimeType) {
        if (!SUPPORTED_TYPES.contains(mimeType.toLowerCase())) {
            throw new AudioRequest.UnsupportedAudioFormatException(
                "Unsupported audio MIME type: '" + mimeType + "'. " +
                "Supported: audio/wav, audio/mp3, audio/mpeg, audio/ogg, " +
                "audio/m4a, audio/flac.");
        }
    }

    private static String normalise(String mimeType) {
        String lower = mimeType.toLowerCase();
        if (lower.equals("audio/mpeg")) return "audio/mp3";
        if (lower.equals("audio/wave")) return "audio/wav";
        return lower;
    }

    private static String extensionFor(String mimeType) {
        return switch (normalise(mimeType)) {
            case "audio/wav"  -> "wav";
            case "audio/mp3"  -> "mp3";
            case "audio/ogg"  -> "ogg";
            case "audio/m4a"  -> "m4a";
            case "audio/flac" -> "flac";
            default           -> "wav";
        };
    }

    private static byte[] buildMultipart(String boundary, byte[] audio,
                                          String ext, String mimeType,
                                          String prompt) {
        String CRLF = "\r\n";
        String dash  = "--";
        StringBuilder sb = new StringBuilder();

        // -- file field --
        sb.append(dash).append(boundary).append(CRLF)
          .append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.")
          .append(ext).append("\"").append(CRLF)
          .append("Content-Type: ").append(normalise(mimeType)).append(CRLF)
          .append(CRLF);

        byte[] header1  = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // -- model field --
        String modelPart = CRLF + dash + boundary + CRLF +
            "Content-Disposition: form-data; name=\"model\"" + CRLF + CRLF +
            "whisper-1" + CRLF;

        // -- optional prompt field --
        String promptPart = "";
        if (prompt != null && !prompt.isBlank()) {
            promptPart = dash + boundary + CRLF +
                "Content-Disposition: form-data; name=\"prompt\"" + CRLF + CRLF +
                prompt + CRLF;
        }

        // -- closing boundary --
        String closing = dash + boundary + dash + CRLF;

        byte[] tail = (modelPart + promptPart + closing)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Assemble: header1 + audio bytes + tail
        byte[] result = new byte[header1.length + audio.length + tail.length];
        System.arraycopy(header1, 0, result, 0, header1.length);
        System.arraycopy(audio,   0, result, header1.length, audio.length);
        System.arraycopy(tail,    0, result, header1.length + audio.length, tail.length);
        return result;
    }
}
