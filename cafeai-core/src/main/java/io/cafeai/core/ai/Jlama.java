package io.cafeai.core.ai;

import io.cafeai.core.internal.LangchainBridge;

/**
 * Factory for Jlama providers — a pure-Java LLM inference engine.
 *
 * <p>Like {@link Ollama}, Jlama runs models locally with no API key and no data
 * leaving your infrastructure. Unlike Ollama, there is <em>no separate server
 * process and no native binary</em> — inference runs in-process on the JVM.
 * Models are pulled from Hugging Face on first use and cached on disk
 * (default: {@code ~/.jlama/models}).
 *
 * <pre>{@code
 *   app.ai(Jlama.llama3());                        // tjake/Llama-3.2-1B-Instruct-JQ4
 *   app.ai(Jlama.tinyLlama());                     // ~1B params — fast, good for tests
 *   app.ai(Jlama.of("tjake/Mistral-7B-Instruct-v0.3-JQ4"));
 *   app.ai(Jlama.cachedIn("/opt/models").model("tjake/Llama-3.2-3B-Instruct-JQ4"));
 * }</pre>
 *
 * <p><strong>Model ids</strong> are Hugging Face repositories ({@code owner/name}).
 * The pre-quantized {@code tjake/*-JQ4} repos load fastest; any safetensors model
 * Jlama supports also works and is quantized on the fly.
 *
 * <p><strong>Required JVM flags.</strong> Jlama loads model classes that import
 * the incubating Vector API, so your app must run with
 * {@code --add-modules jdk.incubator.vector} (Java 20–23) — without it, model
 * construction fails with {@code ClassNotFoundException: jdk.incubator.vector.FloatVector}.
 * On Java 22+ also add {@code --enable-native-access=ALL-UNNAMED} to silence the
 * Panama FFM warning. The {@code cafeai-examples} build sets both.
 */
public final class Jlama {

    private Jlama() {}

    /** Llama 3.2 1B Instruct — the small, quick default. */
    public static AiProvider llama3()    { return of("tjake/Llama-3.2-1B-Instruct-JQ4"); }

    /** TinyLlama 1.1B Chat — the lightest option; handy for tests and CI. */
    public static AiProvider tinyLlama() { return of("tjake/TinyLlama-1.1B-Chat-v1.0-Jlama-Q4"); }

    /** Mistral 7B Instruct v0.3. */
    public static AiProvider mistral()   { return of("tjake/Mistral-7B-Instruct-v0.3-JQ4"); }

    /** Gemma 2 2B Instruct. */
    public static AiProvider gemma2()    { return of("tjake/gemma-2-2b-it-JQ4"); }

    /** Qwen 2.5 0.5B Instruct — smallest chat model here. */
    public static AiProvider qwen2()     { return of("tjake/Qwen2.5-0.5B-Instruct-JQ4"); }

    /**
     * Any Hugging Face model id ({@code owner/name}). Models are cached in
     * Jlama's default directory ({@code ~/.jlama/models}).
     */
    public static AiProvider of(String modelId) {
        return new JlamaProvider(modelId, null);
    }

    /** Creates a builder that caches downloaded models in the given directory. */
    public static JlamaBuilder cachedIn(String modelCachePath) {
        return new JlamaBuilder(modelCachePath);
    }

    public record JlamaBuilder(String modelCachePath) {
        public AiProvider model(String modelId) {
            return new JlamaProvider(modelId, modelCachePath);
        }
    }

    /**
     * Implements {@link LangchainBridge.JlamaProviderAccess} so the bridge can
     * read the model cache path via pattern matching without exposing it on the
     * public {@link AiProvider} interface. A {@code null} path means "use Jlama's
     * default cache directory".
     */
    private record JlamaProvider(String modelId, String modelCachePath)
            implements AiProvider, LangchainBridge.JlamaProviderAccess {
        @Override public String       name() { return "jlama"; }
        @Override public ProviderType type() { return ProviderType.JLAMA; }
    }
}
