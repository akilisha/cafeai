# MILESTONE-16 — Named Providers, TTS, and Technical Debt

**Status: ✅ Complete (8 of 12 phases — 4 dropped or deferred)**

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

## Test counts at close

| Module | Tests |
|--------|-------|
| cafeai-core | 319 |
| cafeai-guardrails | 33 |
| cafeai-memory | 20 |
| cafeai-rag | 13 |
| cafeai-security | 14 |
| cafeai-tools | 12 |
| **Total** | **411** |

---

## New APIs delivered

### Named provider registry
```java
app.ai("tutor",         OpenAI.gpt4o());
app.ai("transcription", OpenAI.whisper());
app.ai("voice",         OpenAI.tts());

app.prompt(msg).provider("tutor").call();
app.audio(p, wav, "audio/wav").provider("transcription").call();
app.synthesise("Hello").provider("voice").call().audioBytes();
```

### TTS synthesis
```java
SynthesisResponse r = app.synthesise("Welcome to the lesson.")
    .provider("voice")
    .call();

byte[] audio = r.audioBytes();   // raw mp3/wav bytes
String fmt   = r.format();       // "mp3"
int    chars = r.characters();   // 26
long   ms    = r.latencyMs();    // synthesis time
```

### StreamingVoicePipeline
```java
StreamingVoicePipeline.create(app)
    .textProvider("tutor")
    .voiceProvider("voice")
    .minChunkSize(40)
    .stream(prompt, chunk -> {
        display(chunk.text());
        audioOutput.play(chunk.audioBytes());
    });
```

### AudioResponse.audioBytes()
```java
// For audio-to-audio models (gpt-4o-audio-preview)
AudioResponse r = app.audio(prompt, wav, "audio/wav").call();
if (r.hasSpeech()) {
    Files.write(Path.of("response.wav"), r.audioBytes());
}
```

### ModelRouter as named provider
```java
app.ai("router", ModelRouter.smart()
    .simple(OpenAI.gpt4oMini())
    .complex(OpenAI.gpt4o()));

app.prompt(msg).provider("router").call(); // routes by complexity
```

---

## Completion definition met

- All 8 active phases ✅
- 319/319 cafeai-core tests pass
- `./gradlew clean build` — BUILD SUCCESSFUL
- Javadoc warnings: 0 (was 18)
- nova-tutor can be built using named providers for all three roles
- `app.synthesise()` produces playable audio bytes
- `StreamingVoicePipeline` coordinates text and voice
