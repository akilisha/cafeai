package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.KeyVendor;
import io.cafeai.core.ai.Grok;
import org.junit.jupiter.api.DisplayName;

/**
 * xAI's Grok. Needs {@code XAI_API_KEY}, or a key saved with {@code cafeai login}.
 *
 * <ul>
 *   <li>{@code GROK_LIVE_MODEL} — the model (default {@code grok-4.7}).</li>
 * </ul>
 */
@DisplayName("Grok — live")
class GrokLiveTest extends ProviderLiveSuite {

    private final String model = env("GROK_LIVE_MODEL", "grok-4.7");

    @Override String label() { return "Grok"; }

    @Override String skipReason() {
        return keyMissing(KeyVendor.GROK);
    }

    @Override AiProvider provider() { return Grok.of(model); }

    /** A short answer may arrive as a single chunk. */
    @Override int minStreamChunks() { return 1; }
}
