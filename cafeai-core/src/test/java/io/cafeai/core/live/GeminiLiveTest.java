package io.cafeai.core.live;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Gemini;
import org.junit.jupiter.api.DisplayName;

/**
 * Google Gemini. Needs {@code GEMINI_API_KEY}.
 *
 * <ul>
 *   <li>{@code GEMINI_LIVE_MODEL} — a fast model that reads images (default {@code gemini-3.6-flash}).</li>
 * </ul>
 */
@DisplayName("Gemini — live")
class GeminiLiveTest extends ProviderLiveSuite {

    private final String model = env("GEMINI_LIVE_MODEL", "gemini-3.6-flash");

    @Override String label() { return "Gemini"; }

    @Override String skipReason() {
        return has("GEMINI_API_KEY") ? null : "GEMINI_API_KEY is not set to a real key";
    }

    @Override AiProvider provider() { return Gemini.of(model); }

    @Override AiProvider visionProvider() { return Gemini.of(model); }

    /** Gemini may send a short answer as a single chunk. */
    @Override int minStreamChunks() { return 1; }
}
