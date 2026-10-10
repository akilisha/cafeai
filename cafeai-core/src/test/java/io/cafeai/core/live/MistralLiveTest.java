package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Mistral;
import org.junit.jupiter.api.DisplayName;

/**
 * Mistral AI. Needs {@code MISTRAL_API_KEY}.
 *
 * <ul>
 *   <li>{@code MISTRAL_LIVE_MODEL} — the model (default {@code mistral-medium-latest}).</li>
 * </ul>
 */
@DisplayName("Mistral — live")
class MistralLiveTest extends ProviderLiveSuite {

    private final String model = env("MISTRAL_LIVE_MODEL", "mistral-medium-latest");

    @Override String label() { return "Mistral"; }

    @Override String skipReason() {
        return has("MISTRAL_API_KEY") ? null : "MISTRAL_API_KEY is not set to a real key";
    }

    @Override AiProvider provider() { return Mistral.of(model); }

    /** A short answer may arrive as a single chunk. */
    @Override int minStreamChunks() { return 1; }
}
