# MILESTONE-16 — Named Providers, TTS, and Technical Debt

**Current Status:** 🔴 Not Started

| Phase | Description | Track | Status |
|-------|-------------|-------|--------|
| 1 | Named provider registry | A | 🔴 |
| 2 | Named provider in ModelRouter | A | 🔴 |
| 3 | `OpenAI.tts()` + TTS synthesis pipeline | A | 🔴 |
| 4 | `AudioResponse.audioBytes()` extension | A | 🔴 |
| 5 | Streaming text to voice | A | 🔴 |
| 6 | `app.vision().stream()` | B | 🔴 |
| 7 | atlas-inbox streaming classification | B | 🔴 |
| 8 | Javadoc warnings | C | 🔴 |
| 9 | McpServer deprecated API | C | 🔴 |
| 10 | Gradle 9.0 deprecation warnings | C | 🔴 |
| 11 | `OpenAiAudioProvider` model ID fix | C | 🔴 |
| 12 | LangChain4j AudioContent tracking | C | 🔴 |

---

## Phase 1 — Named Provider Registry

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `app.ai("tutor", OpenAI.gpt4o())` registers a named provider
- [ ] `app.ai(OpenAI.gpt4o())` (unnamed) continues to work as default
- [ ] `app.prompt(msg).provider("tutor").call()` uses the named provider
- [ ] `app.vision(p, b, m).provider("tutor").call()` uses the named provider
- [ ] `app.audio(p, b, m).provider("transcription").call()` uses the named provider
- [ ] Unknown name throws `IllegalStateException` with helpful message listing registered names
- [ ] Named provider visible in observability output
- [ ] New tests: resolution, fallback to default, unknown name error
- [ ] All existing 359 tests pass

### Key Design
```java
var app = CafeAI.create();
app.ai("tutor",         OpenAI.gpt4o());
app.ai("transcription", OpenAI.whisper());
app.ai("voice",         OpenAI.tts());

app.prompt("explain this").provider("tutor").call();
app.audio(prompt, wav, "audio/wav").provider("transcription").call();
app.synthesise("Hello").provider("voice").call();
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 2 — Named Provider in ModelRouter

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `app.ai("router", ModelRouter.smart().simple(...).complex(...))` works
- [ ] `app.prompt(msg).provider("router").call()` routes by complexity
- [ ] Existing `app.ai(ModelRouter.smart()...)` without name still works
- [ ] All existing ModelRouter tests pass

### Notes
<!-- Add implementation notes here -->

---

## Phase 3 — `OpenAI.tts()` and TTS Synthesis Pipeline

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `OpenAI.tts()` factory method exists (default: alloy voice, mp3 format)
- [ ] `OpenAI.tts(voice, format)` overload exists
- [ ] `AiProvider.supportsTts()` default method returns `false`
- [ ] `OpenAiTtsProvider` returns `true` for `supportsTts()`
- [ ] `CafeAI.synthesise(String text)` interface method exists
- [ ] `SynthesisRequest` and `SynthesisResponse` types in `cafeai-core`
- [ ] `SynthesisResponse.audioBytes()` returns the synthesised audio
- [ ] `CafeAIApp.executeSynthesis()` calls `/v1/audio/speech` directly
- [ ] `TtsNotSupportedException` thrown when provider doesn't support TTS
- [ ] `beforeSynthesis`/`afterSynthesis` hooks in `ObserveBridge`
- [ ] `ObserveBridgeImpl` logs synthesis calls with character count and latency
- [ ] `./gradlew :cafeai-core:compileJava` passes

### API Shape
```java
byte[] audio = app.synthesise("Hello, welcome to today's lesson.")
    .provider("voice")
    .call()
    .audioBytes();
```

### Notes
<!-- Add implementation notes here -->

---

## Phase 4 — `AudioResponse.audioBytes()` Extension

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `AudioResponse.audioBytes()` field added (null by default)
- [ ] `AudioResponse.hasSpeech()` returns true when audioBytes is non-null and non-empty
- [ ] `AudioResponse.Builder.audioBytes(byte[])` setter added
- [ ] Observability console shows `hasSpeech=true` and byte count when applicable
- [ ] All existing `AudioResponse` tests pass (audioBytes null by default)

### Notes
<!-- Add implementation notes here -->

---

## Phase 5 — Streaming Text to Voice

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `StreamingVoicePipeline.create(app, tutorProvider, voiceProvider)` exists
- [ ] `pipeline.stream(prompt, Consumer<byte[]> onAudioChunk)` streams synthesised audio
- [ ] Sentence boundary detection buffers tokens before synthesis
- [ ] Configurable minimum chunk size (`pipeline.minChunkSize(50)`)
- [ ] `StreamingVoiceExample` in `cafeai-examples`
- [ ] `./gradlew :cafeai-examples:compileJava` passes

### Notes
<!-- Add implementation notes here -->

---

## Phase 6 — `app.vision().stream()`

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `stream(Consumer<String> onChunk)` added to `VisionRequest`
- [ ] `executeVisionStream()` implemented in `CafeAIApp`
- [ ] Token budget records usage on stream completion (not per-chunk)
- [ ] POST_LLM guardrails fire on assembled response
- [ ] `afterVisionStream()` observability hook added to `ObserveBridge`
- [ ] `ObserveBridgeImpl` implements `afterVisionStream()`
- [ ] Existing `call()` behaviour unchanged
- [ ] All existing 359 tests pass

### When to use stream() vs call()
- `call()` — when you need the complete response (structured output, routing)
- `stream()` — when you want to display tokens progressively to a user

### Notes
<!-- Add implementation notes here -->

---

## Phase 7 — atlas-inbox Streaming Classification

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `AttachmentTypeClassifier` has a streaming variant alongside structured
- [ ] `--stream` flag in `AtlasInboxProcessor` switches to streaming mode
- [ ] `./gradlew run -Pdry --stream` produces progressive classification output
- [ ] Structured output path unchanged and still passes `testClassification`
- [ ] `README.md` updated with streaming section

### Notes
<!-- Add implementation notes here -->

---

## Phase 8 — Javadoc Warnings

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `./gradlew javadoc` — zero warnings (currently 18+)
- [ ] All `{@link}` references resolve correctly
- [ ] `{@link #prompt()}` fixed to `{@link CafeAI#prompt(String)}`
- [ ] `io.helidon.webserver.WebServer.Builder` reference fixed
- [ ] `io.cafeai.core.observability.ObserveStrategy` fixed to `io.cafeai.observability.ObserveStrategy`
- [ ] `{@link #stream()}` fixed or stub added

### Notes
<!-- Add implementation notes here -->

---

## Phase 9 — McpServer Deprecated API

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `./gradlew :cafeai-tools:compileJava` — zero deprecation warnings
- [ ] Deprecated API replaced with current equivalent
- [ ] Behaviour unchanged

### Notes
Run `./gradlew :cafeai-tools:compileJava -Xlint:deprecation` first to identify the exact API.

---

## Phase 10 — Gradle 9.0 Deprecation Warnings

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `./gradlew build --warning-mode all` — zero Gradle deprecation warnings
- [ ] Gradle wrapper upgraded to 9.0 (or documented as blocked with reason)
- [ ] All 359 tests still pass after any Gradle changes

### Notes
Run `./gradlew build --warning-mode all` first to get the full list.

---

## Phase 11 — `OpenAiAudioProvider` Model ID Fix

**Status:** 🔴 Not Started

### Acceptance Criteria
- [ ] `OpenAiAudioProvider.toChatModel()` uses `this.modelId` not `"gpt-4o-audio-preview"`
- [ ] `AudioMessageBuilder.requiresWhisperEndpoint()` routes based on model ID
- [ ] `OpenAI.whisper()` → Whisper multipart endpoint
- [ ] `OpenAI.of("gpt-4o-audio-preview")` → chat completions audio path
- [ ] Tests cover both routing paths

### Notes
<!-- Add implementation notes here -->

---

## Phase 12 — LangChain4j AudioContent Tracking

**Status:** 🔴 Tracking (not implementation)

### Acceptance Criteria
- [ ] LangChain4j issue #2434 resolved (or closed as won't-fix)
- [ ] If resolved: `AudioMessageBuilder` updated to use native LangChain4j audio
- [ ] If resolved: `transcribeViaWhisper()` and `buildForGemini()` removed
- [ ] All audio tests pass after migration
- [ ] `cafeai-core` no longer needs direct HTTP client for audio

### Notes
Monitor: https://github.com/langchain4j/langchain4j/issues/2434

---

## Completion Definition

MILESTONE-16 is **complete** when:

1. All 12 phases show ✅ Complete
2. Test count >= 390 (359 + new named provider + streaming vision + TTS tests)
3. `./gradlew clean build` — BUILD SUCCESSFUL, zero warnings
4. `./gradlew javadoc` — zero warnings
5. nova-tutor can be built using `app.ai(name, provider)` for all three providers
6. `app.synthesise()` produces playable audio bytes
7. `app.vision().stream()` streams tokens progressively
8. atlas-inbox streaming classification demo works

**What success looks like:**

```java
var app = CafeAI.create();
app.ai("tutor",         OpenAI.gpt4o());
app.ai("transcription", OpenAI.whisper());
app.ai("voice",         OpenAI.tts());
app.budget(TokenBudget.perMinute(60_000));
app.observe(ObserveStrategy.console());

// Tutor reasoning — named provider
TutorResponse step = app.prompt(explanationPrompt)
    .provider("tutor")
    .returning(TutorResponse.class)
    .call(TutorResponse.class);

// Transcription — named provider
String transcript = app.audio("Transcribe this.", wav, "audio/wav")
    .provider("transcription")
    .call().text();

// Synthesis — named provider
byte[] speech = app.synthesise(step.explanation())
    .provider("voice")
    .call().audioBytes();

// Streaming vision — progressive display
app.vision("Classify this attachment.", pdf, "application/pdf")
    .stream(chunk -> System.out.print(chunk));
```
