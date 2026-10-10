package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Nova;
import org.junit.jupiter.api.DisplayName;

/**
 * Amazon Nova (the Nova API, not Bedrock). Needs {@code NOVA_API_KEY}.
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
        return has("NOVA_API_KEY") ? null : "NOVA_API_KEY is not set to a real key";
    }

    @Override AiProvider provider() { return Nova.of(model); }

    /** A short answer may arrive as a single chunk. */
    @Override int minStreamChunks() { return 1; }
}
