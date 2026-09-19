package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import org.junit.jupiter.api.DisplayName;

/**
 * Anthropic. Needs {@code ANTHROPIC_API_KEY}.
 *
 * <ul>
 *   <li>{@code ANTHROPIC_LIVE_MODEL} — a small model that reads images (default
 *       {@code claude-haiku-4-5-20251001}).</li>
 * </ul>
 */
@DisplayName("Anthropic — live")
class AnthropicLiveTest extends ProviderLiveSuite {

    private final String model = env("ANTHROPIC_LIVE_MODEL", "claude-haiku-4-5-20251001");

    @Override String label() { return "Anthropic"; }

    @Override String skipReason() {
        return has("ANTHROPIC_API_KEY") ? null : "ANTHROPIC_API_KEY is not set to a real key";
    }

    @Override AiProvider provider() { return Anthropic.of(model); }

    @Override AiProvider visionProvider() { return Anthropic.of(model); }
}
