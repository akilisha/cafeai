# ROADMAP-16 — Named Providers, TTS, and Technical Debt

> Closes the three framework gaps surfaced by Capstone 5 (nova-tutor),
> adds TTS synthesis as a first-class primitive, and pays down accumulated
> technical debt. ROADMAP-17 handles agents, orchestration, and the 0.2.0 release.

---

## What shipped

**Track A — nova-tutor enablement (all 5 phases complete)**

```java
var app = CafeAI.create();
app.ai("tutor",         OpenAI.gpt4o());
app.ai("transcription", OpenAI.whisper());
app.ai("voice",         OpenAI.tts());

// Reason with a named provider
app.prompt(explanationPrompt).provider("tutor").call();

// Transcribe with a named provider
app.audio("Transcribe this.", wav, "audio/wav").provider("transcription").call().text();

// Synthesise speech
byte[] speech = app.synthesise(step.explanation()).provider("voice").call().audioBytes();

// Coordinate text generation + voice
StreamingVoicePipeline.create(app)
    .textProvider("tutor").voiceProvider("voice")
    .streamAudio("Explain standard deviation", audioOutput::play);
```

**Track C — Technical debt (3 of 5 phases complete)**

- Javadoc warnings reduced from 18 to 0
- ToolRegistry deprecated API updated
- OpenAiAudioProvider model ID fix

---

## Phase inventory

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Named provider registry | ✅ |
| 2 | Named provider in ModelRouter | ✅ |
| 3 | `OpenAI.tts()` + TTS synthesis pipeline | ✅ |
| 4 | `AudioResponse.audioBytes()` | ✅ |
| 5 | `StreamingVoicePipeline` | ✅ |
| 6 | `app.vision().stream()` | ⏸ Deferred |
| 7 | atlas-inbox streaming classification | ⏸ Deferred |
| 8 | Javadoc warnings | ✅ |
| 9 | ToolRegistry deprecated API | ✅ |
| 10 | Gradle 9.0 warnings | ⏸ Deferred |
| 11 | `OpenAiAudioProvider` model ID fix | ✅ |
| 12 | LangChain4j AudioContent tracking | ❌ Dropped |

---

## What was dropped and why

Phases 6 and 7 (streaming vision): the LangChain4j 1.11 streaming handler API
could not be confirmed without jar access on the build machine. Deferred — a
30-minute fix once the correct class name is confirmed.

Phase 10: Gradle 9.0 not yet released. Address when upgrading.

Phase 12: a tracking issue is not a roadmap phase.

---

## What comes next

nova-tutor can now be built. After the capstone, focus shifts to evangelism —
the framework is solid and the story is ready to tell.
