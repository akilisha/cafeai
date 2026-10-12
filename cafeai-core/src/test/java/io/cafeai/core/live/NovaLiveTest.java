package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.KeyVendor;
import io.cafeai.core.ai.Nova;
import org.junit.jupiter.api.DisplayName;

/**
 * Amazon Nova (the Nova API, not Bedrock). Needs {@code NOVA_API_KEY}, or a key saved with {@code cafeai login}.
 *
 * <ul>
 *   <li>{@code NOVA_LIVE_MODEL} — the model (default {@code nova-2-lite-v1}).</li>
 * </ul>
 */
@DisplayName("Nova — live")
class NovaLiveTest extends ProviderLiveSuite {

    private final String model = env("NOVA_LIVE_MODEL", "nova-2-lite-v1");

    @Override String label() { return "Nova"; }

    @Override String skipReason() {
        return keyMissing(KeyVendor.NOVA);
    }

    @Override AiProvider provider() { return Nova.of(model); }

    /** A short answer may arrive as a single chunk. */
    @Override int minStreamChunks() { return 1; }
}
