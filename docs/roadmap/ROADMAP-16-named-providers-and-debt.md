# ROADMAP-16 — Named Providers, TTS, and Technical Debt

> Closes the three framework gaps surfaced by Capstone 5 (`nova-tutor`),
> completes the streaming vision work deferred from ROADMAP-15,
> and pays down the technical debt that has accumulated across 15 roadmap items.
>
> ROADMAP-17 handles the larger framework completeness work (agents,
> orchestration, PgVector, real OTel spans). ROADMAP-16 is deliberately
> tight — nova-tutor enablement plus cleanup. Nothing more.

---

## Context

Three tracks. Clear completion boundary.

**Track A — nova-tutor enablement** closes the gaps that Capstone 5 cannot
work around cleanly. The three-instance workaround for named providers
is functional but documents a hack as a pattern. TTS synthesis currently
has no CafeAI primitive. Streaming text to voice has no coordination model.
These need to be framework primitives before nova-tutor is built properly.

**Track B — streaming vision** was explicitly deferred from ROADMAP-15.
`app.vision().stream()` and the atlas-inbox streaming classification demo
belong here — they are a natural completion of the multimodal story that
the blog series (Post 9) already documented.

**Track C — technical debt** addresses the warnings, stubs, and known
issues that have accumulated without causing failures. 18 Javadoc warnings
in every build. A deprecated API in `McpServer.java`. Gradle 9.0
deprecation warnings. `OpenAiAudioProvider` hardcoding a model name it
should not. These are not urgent but they are wrong, and wrong things
accumulate interest.

---

## Dependency Map

```
Track A
  Phase 1  (named provider registry — CafeAI interface + CafeAIApp)
      └── Phase 2  (named provider in audio/vision/prompt routing)
      └── Phase 3  (OpenAI.tts() factory + TTS synthesis pipeline)
          └── Phase 4  (AudioResponse.audioBytes() extension)
      └── Phase 5  (streaming text to voice coordination)

Track B
  Phase 6  (VisionRequest.stream() — streaming vision)
      └── Phase 7  (atlas-inbox streaming classification demo)

Track C
  Phase 8  (Javadoc warnings — cross-reference fixes)
  Phase 9  (McpServer deprecated API fix)
  Phase 10 (Gradle 9.0 deprecation warnings)
  Phase 11 (OpenAiAudioProvider model ID fix)
  Phase 12 (LangChain4j AudioContent — remove Whisper direct-HTTP when support lands)
```

Tracks A, B, and C are independent. Track C can run in any order.
Track A phases are sequential. Track B is independent of Track A.

---

## Track A — nova-tutor Enablement

### Phase 1 — Named Provider Registry

**Goal:** Add `app.ai(name, provider)` to the `CafeAI` interface alongside
the existing `app.ai(provider)`. Named providers allow multiple providers
to be registered simultaneously, each accessible by name.

**The gap:**

```java
// What nova-tutor needs today (workaround — three instances)
var transcriptionApp = CafeAI.create();
transcriptionApp.ai(OpenAI.whisper());

var tutorApp = CafeAI.create();
tutorApp.ai(OpenAI.gpt4o());

var ttsApp = CafeAI.create();
ttsApp.ai(OpenAI.tts());

// What it should look like (one instance, named providers)
var app = CafeAI.create();
app.ai("tutor",         OpenAI.gpt4o());
app.ai("transcription", OpenAI.whisper());
app.ai("voice",         OpenAI.tts());
```

**New API:**

```java
// CafeAI interface
CafeAI ai(String name, AiProvider provider);

// Usage — prompt with a named provider
app.prompt("explain standard deviation")
   .provider("tutor")
   .call();

// Vision with a named provider
app.vision(prompt, pdfBytes, "application/pdf")
   .provider("tutor")
   .call();

// Audio with a named provider
app.audio(prompt, wavBytes, "audio/wav")
   .provider("transcription")
   .call();
```

**Implementation:**

- `CafeAIApp` adds `Map<String, AiProvider> namedProviders`
- `app.ai(name, provider)` adds to the named map
- `app.ai(provider)` (no name) continues to register as the default provider
- `PromptRequest`, `VisionRequest`, `AudioRequest` all get `.provider(String name)` method
- `executePrompt`, `executeVision`, `executeAudio` resolve provider: named first,
  fall back to default, throw `IllegalStateException` if neither exists
- Named provider registry is visible in observability output:
  `model: openai (gpt-4o) [provider: tutor]`

**Error message for unknown named provider:**

```
IllegalStateException: No provider registered with name 'tutor'.
Registered named providers: [transcription, voice].
Default provider: none.
Call app.ai("tutor", OpenAI.gpt4o()) at startup to register it.
```

**Tasks:**
- [ ] Add `ai(String name, AiProvider provider)` to `CafeAI` interface
- [ ] Add `namedProviders` map to `CafeAIApp`
- [ ] Add `.provider(String name)` to `PromptRequest`, `VisionRequest`, `AudioRequest`
- [ ] Update `executePrompt`, `executeVision`, `executeAudio` to resolve named provider
- [ ] Named provider visible in observability output
- [ ] `./gradlew :cafeai-core:test` — all existing tests pass
- [ ] New tests: named provider resolves, unknown name throws with helpful message,
      default provider used when no name specified

---

### Phase 2 — Named Provider in ModelRouter

**Goal:** `ModelRouter.smart()` currently registers simple and complex providers
as a unit. With named providers, the router should be expressible as a named
provider itself:

```java
app.ai("router", ModelRouter.smart()
    .simple(OpenAI.gpt4oMini())
    .complex(OpenAI.gpt4o()));

// Prompt uses the router — routes by complexity automatically
app.prompt("What is 2+2?").provider("router").call();
```

**Tasks:**
- [ ] `ModelRouter` implements `AiProvider` so it can be registered by name
- [ ] `.provider("router")` resolves to the router and applies routing logic
- [ ] Existing `app.ai(ModelRouter.smart()...)` without a name continues to work

---

### Phase 3 — `OpenAI.tts()` and TTS Synthesis Pipeline

**Goal:** Add text-to-speech synthesis as a first-class CafeAI operation.
TTS is the inverse of audio transcription: text in, audio bytes out.

**New factory:**

```java
// OpenAI.java
/**
 * OpenAI TTS — text-to-speech synthesis.
 * Supported voices: alloy, echo, fable, onyx, nova, shimmer.
 * Supported formats: mp3, opus, aac, flac, wav, pcm.
 */
public static AiProvider tts() {
    return tts("alloy", "mp3");
}

public static AiProvider tts(String voice, String format) {
    return new OpenAiTtsProvider(voice, format);
}
```

**New entry point:**

```java
// CafeAI interface
/**
 * Synthesises text to speech using the registered TTS provider.
 *
 * <pre>{@code
 *   byte[] audio = app.synthesise("Hello, welcome to today's lesson.")
 *       .provider("voice")
 *       .call()
 *       .audioBytes();
 * }</pre>
 */
SynthesisRequest synthesise(String text);
```

**New types:**

```java
// SynthesisRequest — mirrors AudioRequest structure
public final class SynthesisRequest {
    private final String text;
    private String providerName;
    private final SynthesisExecutor executor;

    public SynthesisRequest provider(String name) { ... }
    public SynthesisResponse call() { ... }
}

// SynthesisResponse
public final class SynthesisResponse {
    public byte[]  audioBytes() { ... }  // the synthesised audio
    public String  format()     { ... }  // "mp3", "wav", etc.
    public String  modelId()    { ... }
    public int     characters() { ... }  // characters in the input text
}
```

**Implementation note:** OpenAI TTS uses `/v1/audio/speech` (POST with JSON body,
returns binary audio). This is a different endpoint from both chat completions
and Whisper transcription. Like Whisper, it requires direct HTTP — LangChain4j
does not wrap it. `SynthesisExecutor` in `CafeAIApp` calls the endpoint directly
via `java.net.http.HttpClient`.

**Tasks:**
- [ ] `OpenAI.tts()` and `OpenAI.tts(voice, format)` factory methods
- [ ] `OpenAiTtsProvider` record with `supportsTts() = true`
- [ ] `SynthesisRequest` and `SynthesisResponse` types in `cafeai-core`
- [ ] `CafeAI.synthesise(String text)` interface method
- [ ] `CafeAIApp.executeSynthesis()` — direct HTTP to `/v1/audio/speech`
- [ ] `beforeSynthesis`/`afterSynthesis` hooks in `ObserveBridge`
- [ ] `ObserveBridgeImpl` implements both hooks
- [ ] `SupportsTts` capability check on `AiProvider` (default `false`)
- [ ] Error message: `TtsNotSupportedException` when provider doesn't support TTS
- [ ] `./gradlew :cafeai-core:compileJava` passes

---

### Phase 4 — `AudioResponse.audioBytes()` Extension

**Goal:** `AudioResponse` currently only returns text. When the registered
provider is a TTS provider, the response is audio bytes. Extend `AudioResponse`
to carry both.

```java
// Extended AudioResponse
public final class AudioResponse {
    public String text()        { return text; }       // transcript or model text
    public byte[] audioBytes()  { return audioBytes; } // TTS output, null for transcription
    public boolean hasSpeech()  { return audioBytes != null && audioBytes.length > 0; }
    // ... existing fields
}
```

**Note:** `SynthesisResponse` (Phase 3) and `AudioResponse` may converge as the
design clarifies. The Phase 3 approach (separate type) is safer — it avoids
overloading `AudioResponse` with a field that is null in 90% of uses. This
design decision is explicitly deferred to implementation.

**Tasks:**
- [ ] Add `audioBytes` field to `AudioResponse.Builder`
- [ ] `AudioResponse.hasSpeech()` convenience method
- [ ] Update `ObserveBridgeImpl` audio console to show `hasSpeech=true` and byte count
- [ ] Existing tests still pass (audioBytes is null by default)

---

### Phase 5 — Streaming Text to Voice

**Goal:** Enable `app.prompt().stream()` output to be piped directly into
TTS synthesis, producing audio as tokens arrive rather than after the full
response is assembled.

```java
// Stream text and synthesise each chunk
app.prompt(explanationPrompt)
   .provider("tutor")
   .stream(chunk -> {
       byte[] audio = app.synthesise(chunk).provider("voice").call().audioBytes();
       audioOutput.play(audio);
   });
```

This is a coordination pattern, not a new primitive. The components already
exist: `app.prompt().stream()` (existing), `app.synthesise()` (Phase 3).
The developer composes them. CafeAI does not need a single entry point for
stream-to-voice — the composition is the pattern.

**What CafeAI does provide:** a `StreamingVoicePipeline` helper that handles
the buffering concern (chunks should be synthesised in sentence-length units,
not token-by-token, for natural-sounding speech):

```java
// Helper — buffers tokens into sentence-length units before synthesising
var pipeline = StreamingVoicePipeline.create(app, "tutor", "voice");
pipeline.stream(explanationPrompt, audioOutput::play);
```

**Tasks:**
- [ ] `StreamingVoicePipeline` class in `cafeai-core`
- [ ] Sentence boundary detection for buffering (period, question mark, exclamation)
- [ ] Configurable minimum chunk size before synthesis
- [ ] `./gradlew :cafeai-core:compileJava` passes
- [ ] Example in `cafeai-examples`: `StreamingVoiceExample`

---

## Track B — Streaming Vision (Deferred from ROADMAP-15)

### Phase 6 — `app.vision().stream()`

**Goal:** Add streaming support to `VisionRequest`. Vision calls can take
3–8 seconds for classification or extraction — streaming gives the developer
something to show the user immediately.

```java
// Current — blocks until complete
VisionResponse r = app.vision(prompt, pdfBytes, "application/pdf").call();

// New — streams tokens as generated
app.vision(prompt, pdfBytes, "application/pdf")
   .stream(chunk -> System.out.print(chunk));

// With SSE in an HTTP handler
app.vision(prompt, imageBytes, "image/jpeg")
   .stream(res.sseEmitter());
```

**Design constraints:**
- `stream()` is meaningful for text generation (classification description,
  extraction reasoning). It is not meaningful for structured output — you need
  the complete JSON before parsing.
- `VisionResponse` is not returned from `stream()` — consumed via callback.
- Token budget applies to streaming calls (count tokens on completion).
- POST_LLM guardrails apply to the assembled stream before delivery.

**Tasks:**
- [ ] `stream(Consumer<String> onChunk)` added to `VisionRequest`
- [ ] `executeVisionStream()` in `CafeAIApp`
- [ ] Token budget records usage on stream completion
- [ ] POST_LLM guardrails fire on assembled response
- [ ] `afterVisionStream()` observability hook
- [ ] `./gradlew :cafeai-core:test` — all existing tests pass

---

### Phase 7 — atlas-inbox Streaming Classification Demo

**Goal:** Demonstrate `app.vision().stream()` in `atlas-inbox` for the
attachment classification step.

```java
// Stream the classification reasoning as it arrives
System.out.print("  Classifying: ");
app.vision(buildPrompt(), pdfBytes, "application/pdf")
   .stream(chunk -> System.out.print(chunk));
System.out.println();

// For routing decisions, still use structured call()
AttachmentClassification result = app.vision(buildPrompt(), pdfBytes, "application/pdf")
    .returning(AttachmentClassification.class)
    .call(AttachmentClassification.class);
```

**Tasks:**
- [ ] `AttachmentTypeClassifier` updated with streaming variant
- [ ] `--stream` flag in `AtlasInboxProcessor` switches to streaming mode
- [ ] `README.md` updated with streaming section
- [ ] `./gradlew run -Pdry --stream` produces progressive classification output

---

## Track C — Technical Debt

### Phase 8 — Javadoc Cross-Reference Warnings

18 warnings in every build. None cause failures. All are wrong.

```
CafeAI.java:437: warning: reference not found: #prompt()
CafeAI.java:981: warning: reference not found: io.helidon.webserver.WebServer.Builder
Locals.java:47:  warning: reference not found: io.cafeai.core.observability.ObserveStrategy
PromptRequest.java:9: warning: reference not found: #stream()
```

**Tasks:**
- [ ] Fix `{@link #prompt()}` → `{@link CafeAI#prompt(String)}`
- [ ] Fix `{@link io.helidon.webserver.WebServer.Builder}` → use `{@code WebServer.Builder}`
      or add `cafeai-connect` as a Javadoc dependency
- [ ] Fix `{@link io.cafeai.core.observability.ObserveStrategy}` →
      `{@link io.cafeai.observability.ObserveStrategy}` (wrong package)
- [ ] Fix `{@link #stream()}` → document that stream is not yet implemented
      or add the method stub
- [ ] Fix remaining cross-reference warnings
- [ ] `./gradlew javadoc` — zero warnings

---

### Phase 9 — McpServer Deprecated API

```
cafeai-tools:compileJava: McpServer.java uses or overrides a deprecated API.
```

**Tasks:**
- [ ] Run `./gradlew :cafeai-tools:compileJava -Xlint:deprecation` to identify exact usage
- [ ] Replace deprecated API with current equivalent
- [ ] `./gradlew :cafeai-tools:compileJava` — zero warnings

---

### Phase 10 — Gradle 9.0 Deprecation Warnings

```
Deprecated Gradle features were used in this build,
making it incompatible with Gradle 9.0.
```

**Tasks:**
- [ ] Run `./gradlew build --warning-mode all` to list all deprecations
- [ ] Address each deprecation in root `build.gradle` and module `build.gradle` files
- [ ] `./gradlew build --warning-mode all` — zero deprecation warnings
- [ ] Verify Gradle wrapper can be upgraded to 9.0 without build failures

---

### Phase 11 — `OpenAiAudioProvider` Model ID Fix

```java
// Current — hardcodes gpt-4o-audio-preview regardless of registered model
@Override
public dev.langchain4j.model.chat.ChatModel toChatModel() {
    return dev.langchain4j.model.openai.OpenAiChatModel.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o-audio-preview")  // ← hardcoded
        .build();
}
```

`OpenAI.whisper()` creates an `OpenAiAudioProvider("whisper-1")`. The model ID
is `whisper-1`. But `toChatModel()` ignores it and always uses `gpt-4o-audio-preview`.

**Fix:** Respect the registered model ID. If the model is `whisper-1`, use the
Whisper transcription path. If it is `gpt-4o-audio-preview` or another audio
model, use chat completions.

**Tasks:**
- [ ] `OpenAiAudioProvider.toChatModel()` uses `this.modelId` not a hardcoded string
- [ ] `AudioMessageBuilder.requiresWhisperEndpoint()` checks model ID, not just provider type
- [ ] `OpenAI.whisper()` → Whisper endpoint
- [ ] `OpenAI.tts("gpt-4o-audio-preview")` → chat completions audio path
- [ ] Tests cover both routing paths

---

### Phase 12 — LangChain4j AudioContent Tracking

LangChain4j 1.11 does not support `AudioContent` for OpenAI's chat completions
API. `AudioMessageBuilder` works around this with direct HTTP. When LangChain4j
adds `WhisperTranscriptionModel` or audio support in `OpenAiChatModel`, the
direct HTTP path can be removed.

**This phase is a tracking issue, not implementation work.**

**Tasks:**
- [ ] Monitor LangChain4j releases for `#2434` resolution
  (https://github.com/langchain4j/langchain4j/issues/2434)
- [ ] When support lands: update `langchain4jVersion` in root `build.gradle`
- [ ] Replace `AudioMessageBuilder.transcribeViaWhisper()` with LangChain4j native call
- [ ] Remove `buildForGemini()` / `requiresWhisperEndpoint()` routing
- [ ] All `./gradlew :cafeai-core:test` pass after migration

---

## What this roadmap does NOT cover

- **Agents / ReAct loop** — `cafeai-agents` module. ROADMAP-17.
- **Multi-agent orchestration** — Structured Concurrency. ROADMAP-17.
- **PgVector implementation** — specced, not implemented. ROADMAP-17.
- **Real OpenTelemetry spans** — `ObserveStrategy.otel()` is partial. ROADMAP-17.
- **`Retriever.hybrid()`** — specced, not implemented. ROADMAP-17.
- **Maven Central publication** — `cafeai` 0.2.0 release. After ROADMAP-17.
- **Conference talk preparation** — After ROADMAP-17 and nova-tutor.
