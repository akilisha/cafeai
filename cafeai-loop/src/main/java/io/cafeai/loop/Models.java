package io.cafeai.loop;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.Gemini;
import io.cafeai.core.ai.Grok;
import io.cafeai.core.ai.Mistral;
import io.cafeai.core.ai.Nova;
import io.cafeai.core.ai.Ollama;
import io.cafeai.core.ai.OpenAI;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A command's model (ROADMAP-03, 2.2 and 2.3): named {@code provider:model} in the settings, the
 * command's own ({@code models.<command>}) or {@code models.default}. The loop names no model
 * itself: model ids go stale. Each is one of CafeAI's providers with no credentials of the loop's,
 * so CafeAI's credential lookup decides: code, the vendor's variable, then {@code cafeai login}.
 */
public final class Models {

    private static final Map<String, Function<String, AiProvider>> PROVIDERS = new LinkedHashMap<>();

    /**
     * The most an Anthropic model may write in one reply. Anthropic's API needs a number, and without
     * one a command gets LangChain4j's 1,024 tokens: too few to write a design spec or a roadmap in one
     * tool call, so the reply is cut off before it says anything. Every current Claude model allows this
     * much; a reply costs only what it writes. The other providers, given none, use the model's own limit.
     */
    static final int ANTHROPIC_MAX_OUTPUT = 32_000;

    /**
     * How long one model call may take. CafeAI's default is a chat call's minute; writing a whole
     * design spec takes several, and a local model longer still.
     */
    static final Duration TIMEOUT = Duration.ofMinutes(10);

    static {
        PROVIDERS.put("anthropic", id -> Anthropic.of(id).withMaxTokens(ANTHROPIC_MAX_OUTPUT));
        PROVIDERS.put("openai", OpenAI::of);
        PROVIDERS.put("gemini", Gemini::of);
        PROVIDERS.put("grok", Grok::of);
        PROVIDERS.put("mistral", Mistral::of);
        PROVIDERS.put("nova", Nova::of);
        PROVIDERS.put("ollama", Ollama::of);
    }

    /** The providers a model can be named by, in the settings' {@code provider:model}. */
    public static final List<String> NAMES = List.copyOf(PROVIDERS.keySet());

    private Models() {}

    /** The model a command uses: its own, else the default; refused, with how to name one, when neither is set. */
    public static AiProvider forCommand(Settings settings, String command) {
        return provider(settings.model(command).orElseThrow(() -> new IllegalStateException(
                "No model is named for `cafeai " + command + "`. Name one in " + Settings.FILE + ", as provider:model,"
                        + " with models.default (every command) or models." + command + " (this one):\n"
                        + "  models:\n    default: anthropic:<model-id>      # or openai:, gemini:, grok:, mistral:, nova:, ollama:\n"
                        + "The loop names no model itself: model ids go stale.")));
    }

    /** CafeAI's provider for {@code provider:model}; only the first colon splits ({@code ollama:qwen3:8b}). */
    public static AiProvider provider(String id) {
        String problem = problem(id);
        if (problem != null) throw new IllegalArgumentException(problem);
        int colon = id.indexOf(':');
        return PROVIDERS.get(id.substring(0, colon)).apply(id.substring(colon + 1)).withTimeout(TIMEOUT);
    }

    /** What is wrong with {@code id} as {@code provider:model}, or {@code null} when it's well formed. */
    static String problem(String id) {
        int colon = id == null ? -1 : id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return "'" + id + "' isn't a model named provider:model (anthropic:<model-id>, ollama:<model-id>, ...)";
        }
        if (!PROVIDERS.containsKey(id.substring(0, colon))) {
            return "'" + id.substring(0, colon) + "' isn't a provider; they are " + String.join(", ", NAMES);
        }
        return null;
    }
}
