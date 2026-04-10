package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AudioRequest;
import io.cafeai.core.ai.AudioResponse;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.observability.ObserveStrategy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * AudioTranscriptionExample — the third CafeAI modality: {@code app.audio()}.
 *
 * <p>Demonstrates two audio entry points side by side:
 * <ol>
 *   <li>Plain transcription — {@code app.audio(...).call()} returns text</li>
 *   <li>Structured extraction — {@code .returning(CallSummary.class).call(CallSummary.class)}
 *       returns a typed record with no boilerplate parsing</li>
 * </ol>
 *
 * <h2>The three modalities — one API shape</h2>
 * <pre>
 *   // Text
 *   PromptResponse  r = app.prompt("summarise this ticket").call();
 *
 *   // Vision
 *   VisionResponse  r = app.vision("is this an invoice?", pdfBytes, "application/pdf").call();
 *
 *   // Audio
 *   AudioResponse   r = app.audio("transcribe this call", wavBytes, "audio/wav").call();
 * </pre>
 *
 * All three support structured output, session memory, guardrails, observability,
 * and token budget — through the same pipeline.
 *
 * <h2>Guardrails on transcripts</h2>
 * PII guardrails fire on the transcript text exactly as they fire on text prompts.
 * If a phone number appears in the audio transcript, it is caught and blocked
 * before reaching the caller. The developer writes no PII-handling code.
 *
 * <h2>Prerequisites</h2>
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 * </pre>
 * {@code gpt-4o} is required — it is the audio-capable model routed to by
 * {@code app.audio()} when using {@code OpenAI.gpt4o()}.
 *
 * <h2>Running</h2>
 * <pre>
 *   # In cafeai-examples/build.gradle, set:
 *   #   mainClass = 'io.cafeai.examples.AudioTranscriptionExample'
 *
 *   ./gradlew :cafeai-examples:run
 * </pre>
 *
 * <h2>Sample audio files</h2>
 * This example ships with two synthetic WAV files in
 * {@code src/main/resources/audio/}:
 * <ul>
 *   <li>{@code support-call.wav} — a simulated customer support call with
 *       a phone number mentioned (triggers PII guardrail)</li>
 *   <li>{@code team-meeting.wav} — a simulated team meeting with action items</li>
 * </ul>
 * Replace either file with your own WAV to try real audio.
 *
 * <h2>Whisper vs gpt-4o</h2>
 * {@code OpenAI.whisper()} is the purpose-built transcription model.
 * {@code OpenAI.gpt4o()} handles audio natively via the chat completions API.
 * The example uses {@code gpt-4o} because it supports both transcription
 * <em>and</em> structured reasoning in a single call — no two-step pipeline needed.
 * {@code whisper()} is the right choice when you want maximum transcription
 * accuracy and will handle summarisation yourself.
 */
public class AudioTranscriptionExample {

    // ── Structured output records ─────────────────────────────────────────────

    /**
     * Structured result from a support call.
     * Extracted in a single {@code app.audio().returning(CallSummary.class)} call.
     */
    record CallSummary(
        String  customerName,
        String  issueType,       // BILLING, TECHNICAL, ACCOUNT, OTHER
        String  summary,
        boolean resolved,
        String  followUpAction   // null if resolved
    ) {}

    /**
     * Structured result from a team meeting recording.
     */
    record MeetingSummary(
        String       meetingTitle,
        List<String> actionItems,
        List<String> decisions,
        String       nextMeetingDate  // null if not mentioned
    ) {}

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        // ── CafeAI setup ──────────────────────────────────────────────────────
        var app = CafeAI.create();

        // gpt-4o supports audio input natively — supportsAudio() = true.
        // OpenAI.whisper() is the alternative for pure transcription workloads.
        app.ai(OpenAI.gpt4o());

        app.system("""
            You are a professional transcription and analysis assistant.
            You transcribe audio accurately, extract structured information,
            and identify key details. Be precise and concise.
            """);

        // Guardrails apply to the transcript text — not to the raw audio.
        // PII in speech is caught the same way PII in typed text is caught.
        app.guard(GuardRail.pii());

        app.observe(ObserveStrategy.console());

        // ── Load sample audio files ───────────────────────────────────────────
        byte[] supportCallAudio  = loadAudio("audio/support-call.wav");
        byte[] teamMeetingAudio  = loadAudio("audio/team-meeting.wav");

        System.out.println("☕ AudioTranscriptionExample");
        System.out.println("===========================");
        System.out.println();

        // ── Demo 1: Plain transcription ───────────────────────────────────────
        System.out.println("── Demo 1: Plain transcription ─────────────────────");
        System.out.println();

        AudioResponse transcript = app.audio(
            "Transcribe this customer support call verbatim.",
            supportCallAudio, "audio/wav").call();

        System.out.println("Transcript:");
        System.out.println(transcript.text());
        System.out.println();
        System.out.printf("Tokens used: %d prompt + %d output = %d total%n",
            transcript.promptTokens(),
            transcript.outputTokens(),
            transcript.totalTokens());
        System.out.println();

        // ── Demo 2: Structured extraction from a support call ─────────────────
        System.out.println("── Demo 2: Structured extraction (support call) ─────");
        System.out.println();

        CallSummary summary = app.audio(
            "Extract the key details from this customer support call.",
            supportCallAudio, "audio/wav")
            .returning(CallSummary.class)
            .call(CallSummary.class);

        System.out.println("Extracted CallSummary:");
        System.out.println("  Customer:      " + summary.customerName());
        System.out.println("  Issue type:    " + summary.issueType());
        System.out.println("  Summary:       " + summary.summary());
        System.out.println("  Resolved:      " + summary.resolved());
        System.out.println("  Follow-up:     " + summary.followUpAction());
        System.out.println();

        // ── Demo 3: Structured extraction from a meeting ──────────────────────
        System.out.println("── Demo 3: Structured extraction (team meeting) ─────");
        System.out.println();

        MeetingSummary meeting = app.audio(
            "Extract the action items, decisions, and next meeting date from this recording.",
            teamMeetingAudio, "audio/mp3")
            .returning(MeetingSummary.class)
            .call(MeetingSummary.class);

        System.out.println("Extracted MeetingSummary:");
        System.out.println("  Meeting:       " + meeting.meetingTitle());
        System.out.println("  Action items:");
        meeting.actionItems().forEach(a -> System.out.println("    - " + a));
        System.out.println("  Decisions:");
        meeting.decisions().forEach(d -> System.out.println("    - " + d));
        System.out.println("  Next meeting:  " + meeting.nextMeetingDate());
        System.out.println();

        // ── Demo 4: Session memory across audio turns ─────────────────────────
        System.out.println("── Demo 4: Session memory across audio turns ────────");
        System.out.println();
        System.out.println("(Demonstrates that session context carries across");
        System.out.println(" multiple audio calls — stored as text, not bytes.)");
        System.out.println();

        // First audio call — establishes session context
        app.audio("Transcribe this call and note the main customer complaint.",
            supportCallAudio, "audio/wav")
            .session("demo-session")
            .call();

        // Second call — text prompt that references prior audio session
        var followUp = app.prompt(
            "Based on the support call we just reviewed, " +
            "draft a follow-up email to the customer.")
            .session("demo-session")  // same session — has the transcript
            .call();

        System.out.println("Follow-up email draft:");
        System.out.println(followUp.text());
        System.out.println();

        System.out.println("===========================");
        System.out.println("AudioTranscriptionExample complete.");
        System.out.println();
        System.out.println("Key observations:");
        System.out.println("  1. app.audio() mirrors app.prompt() and app.vision()");
        System.out.println("  2. .returning(Class) works identically across all three modalities");
        System.out.println("  3. PII guardrails fired on transcript text, not raw audio");
        System.out.println("  4. Session memory stored text — audio bytes were never persisted");
        System.out.println("  5. Demo 4 mixed audio and text in the same session seamlessly");
    }

    // ── Audio loader ──────────────────────────────────────────────────────────

    /**
     * Loads a WAV or MP3 file from {@code src/main/resources/audio/}.
     * If the file is not found, generates a minimal silent WAV in memory
     * so the example compiles and starts without real audio files.
     */
    private static byte[] loadAudio(String resourcePath) throws IOException {
        URL url = AudioTranscriptionExample.class
            .getClassLoader().getResource(resourcePath);

        if (url != null) {
            try {
                return Files.readAllBytes(Path.of(url.toURI()));
            } catch (URISyntaxException e) {
                // fall through to synthetic audio
            }
        }

        System.out.printf("[audio] '%s' not found — using synthetic silent WAV%n",
            resourcePath);
        return syntheticSilentWav();
    }

    /**
     * Generates a minimal valid WAV file containing 1 second of silence at 16kHz mono.
     * Used when real audio files are not present, so the example starts cleanly
     * without requiring audio hardware or pre-recorded files.
     *
     * <p>Replace the audio files in {@code src/main/resources/audio/} with real
     * recordings to get meaningful transcription output.
     */
    private static byte[] syntheticSilentWav() {
        int sampleRate  = 16_000;
        int numSamples  = sampleRate;          // 1 second
        int dataSize    = numSamples * 2;      // 16-bit = 2 bytes per sample
        int totalSize   = 44 + dataSize;       // WAV header (44 bytes) + data

        byte[] wav = new byte[totalSize];

        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        putInt(wav, 4, totalSize - 8);         // chunk size
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        // fmt sub-chunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        putInt(wav, 16, 16);                   // sub-chunk size (PCM)
        putShort(wav, 20, (short) 1);          // audio format (PCM)
        putShort(wav, 22, (short) 1);          // num channels (mono)
        putInt(wav, 24, sampleRate);           // sample rate
        putInt(wav, 28, sampleRate * 2);       // byte rate
        putShort(wav, 32, (short) 2);          // block align
        putShort(wav, 34, (short) 16);         // bits per sample

        // data sub-chunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        putInt(wav, 40, dataSize);

        // samples — all zero (silence)
        return wav;
    }

    private static void putInt(byte[] b, int offset, int value) {
        b[offset]     = (byte)  value;
        b[offset + 1] = (byte) (value >>  8);
        b[offset + 2] = (byte) (value >> 16);
        b[offset + 3] = (byte) (value >> 24);
    }

    private static void putShort(byte[] b, int offset, short value) {
        b[offset]     = (byte)  value;
        b[offset + 1] = (byte) (value >> 8);
    }
}
