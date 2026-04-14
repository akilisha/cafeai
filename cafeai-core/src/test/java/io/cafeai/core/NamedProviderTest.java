package io.cafeai.core;

import io.cafeai.core.ai.*;
import io.cafeai.core.ai.ModelRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ROADMAP-16 Phase 1: Named Provider Registry.
 *
 * <p>Covers:
 * <ul>
 *   <li>app.ai(name, provider) — registration</li>
 *   <li>.provider(name) on PromptRequest, VisionRequest, AudioRequest</li>
 *   <li>Named provider resolution — takes precedence over default</li>
 *   <li>Unknown name throws with helpful message</li>
 *   <li>Default provider still works when no name specified</li>
 * </ul>
 */
class NamedProviderTest {

    // ── Registration ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("app.ai(name, provider) registration")
    class Registration {

        @Test
        @DisplayName("registers a named provider and returns app for chaining")
        void namedProvider_registersAndChains() {
            var app = CafeAI.create();
            assertThat(app.ai("tutor", mockProvider("tutor-model", "ok"))).isSameAs(app);
        }

        @Test
        @DisplayName("null name throws IllegalArgumentException")
        void nullName_throws() {
            var app = CafeAI.create();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> app.ai(null, mockProvider("m", "ok")))
                .withMessageContaining("name");
        }

        @Test
        @DisplayName("blank name throws IllegalArgumentException")
        void blankName_throws() {
            var app = CafeAI.create();
            assertThatIllegalArgumentException()
                .isThrownBy(() -> app.ai("  ", mockProvider("m", "ok")))
                .withMessageContaining("name");
        }

        @Test
        @DisplayName("null provider throws NullPointerException")
        void nullProvider_throws() {
            var app = CafeAI.create();
            assertThatNullPointerException()
                .isThrownBy(() -> app.ai("tutor", null));
        }

        @Test
        @DisplayName("multiple named providers can be registered simultaneously")
        void multipleNamedProviders_register() {
            var app = CafeAI.create();
            assertThatNoException().isThrownBy(() -> {
                app.ai("tutor",         mockProvider("gpt-4o", "answer"));
                app.ai("transcription", mockProvider("whisper-1", "transcript"));
                app.ai("voice",         mockProvider("tts-1", "audio"));
            });
        }
    }

    // ── PromptRequest.provider() ──────────────────────────────────────────────

    @Nested
    @DisplayName("PromptRequest.provider()")
    class PromptRequestProvider {

        @Test
        @DisplayName("provider() stores the name and returns this for chaining")
        void provider_storesNameAndChains() {
            var app = CafeAI.create();
            app.ai("tutor", mockProvider("gpt-4o", "ok"));
            var req = app.prompt("test").provider("tutor");
            assertThat(req.providerName()).isEqualTo("tutor");
            assertThat(req).isNotNull();  // returns this
        }

        @Test
        @DisplayName("named provider resolves correctly — uses named not default")
        void namedProvider_usedOverDefault() {
            var app = CafeAI.create();
            var defaultCapture  = new CapturingMock("default-response");
            var namedCapture    = new CapturingMock("named-response");

            app.ai(defaultCapture);            // default provider
            app.ai("tutor", namedCapture);     // named provider

            var response = app.prompt("hello").provider("tutor").call();

            assertThat(response.text()).isEqualTo("named-response");
            assertThat(namedCapture.wasCalled).isTrue();
            assertThat(defaultCapture.wasCalled).isFalse();
        }

        @Test
        @DisplayName("without .provider(), default provider is used")
        void withoutProvider_defaultUsed() {
            var app = CafeAI.create();
            var defaultCapture = new CapturingMock("default-response");
            var namedCapture   = new CapturingMock("named-response");

            app.ai(defaultCapture);
            app.ai("tutor", namedCapture);

            var response = app.prompt("hello").call();  // no .provider()

            assertThat(response.text()).isEqualTo("default-response");
            assertThat(defaultCapture.wasCalled).isTrue();
            assertThat(namedCapture.wasCalled).isFalse();
        }

        @Test
        @DisplayName("unknown provider name throws IllegalStateException with helpful message")
        void unknownProviderName_throwsWithHelpfulMessage() {
            var app = CafeAI.create();
            app.ai("tutor",         mockProvider("gpt-4o", "ok"));
            app.ai("transcription", mockProvider("whisper", "ok"));

            assertThatThrownBy(() ->
                app.prompt("hello").provider("unknown-provider").call())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown-provider")
                .hasMessageContaining("tutor")
                .hasMessageContaining("transcription");
        }

        @Test
        @DisplayName("no provider registered at all throws IllegalStateException")
        void noProviderRegistered_throws() {
            var app = CafeAI.create();
            assertThatThrownBy(() -> app.prompt("hello").call())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ai");
        }
    }

    // ── VisionRequest.provider() ──────────────────────────────────────────────

    @Nested
    @DisplayName("VisionRequest.provider()")
    class VisionRequestProvider {

        @Test
        @DisplayName("provider() stores name and returns this")
        void provider_storesAndChains() {
            var app = CafeAI.create();
            app.ai("vision-model", visionMock("ok"));
            var req = app.vision("classify", new byte[]{1}, "image/jpeg")
                .provider("vision-model");
            assertThat(req.providerName()).isEqualTo("vision-model");
        }

        @Test
        @DisplayName("unknown vision provider name throws IllegalStateException")
        void unknownVisionProvider_throws() {
            var app = CafeAI.create();
            app.ai("vision-model", visionMock("ok"));

            assertThatThrownBy(() ->
                app.vision("classify", new byte[]{1}, "image/jpeg")
                    .provider("no-such-provider")
                    .call())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-provider");
        }
    }

    // ── AudioRequest.provider() ───────────────────────────────────────────────

    @Nested
    @DisplayName("AudioRequest.provider()")
    class AudioRequestProvider {

        @Test
        @DisplayName("provider() stores name and returns this")
        void provider_storesAndChains() {
            var app = CafeAI.create();
            app.ai("transcription", mockProvider("whisper-1", "ok"));
            var req = app.audio("transcribe", new byte[]{1}, "audio/wav")
                .provider("transcription");
            assertThat(req.providerName()).isEqualTo("transcription");
        }
    }

    // ── ModelRouter as named provider ────────────────────────────────────────

    @Nested
    @DisplayName("ModelRouter as named provider")
    class ModelRouterAsNamedProvider {

        @Test
        @DisplayName("ModelRouter can be registered as a named provider")
        void modelRouter_canBeRegisteredByName() {
            var app = CafeAI.create();
            var router = ModelRouter.smart()
                .simple(mockProvider("gpt-4o-mini", "simple-response"))
                .complex(mockProvider("gpt-4o", "complex-response"));

            assertThatNoException().isThrownBy(() -> app.ai("router", router));
        }

        @Test
        @DisplayName("short prompt routes to simple model via named router")
        void shortPrompt_routesToSimpleModel() {
            var app = CafeAI.create();
            var simpleCapture  = new CapturingMock("simple-response");
            var complexCapture = new CapturingMock("complex-response");

            app.ai("router", ModelRouter.smart()
                .simple(simpleCapture)
                .complex(complexCapture));

            // Short message (<= 500 chars) → simple model
            var response = app.prompt("What is 2+2?").provider("router").call();

            assertThat(response.text()).isEqualTo("simple-response");
            assertThat(simpleCapture.wasCalled).isTrue();
            assertThat(complexCapture.wasCalled).isFalse();
        }

        @Test
        @DisplayName("long prompt routes to complex model via named router")
        void longPrompt_routesToComplexModel() {
            var app = CafeAI.create();
            var simpleCapture  = new CapturingMock("simple-response");
            var complexCapture = new CapturingMock("complex-response");

            app.ai("router", ModelRouter.smart()
                .simple(simpleCapture)
                .complex(complexCapture));

            // Long message (> 500 chars) → complex model
            String longMessage = "x".repeat(501);
            var response = app.prompt(longMessage).provider("router").call();

            assertThat(response.text()).isEqualTo("complex-response");
            assertThat(complexCapture.wasCalled).isTrue();
            assertThat(simpleCapture.wasCalled).isFalse();
        }

        @Test
        @DisplayName("existing app.ai(ModelRouter) without name still works")
        void existingModelRouterRegistration_stillWorks() {
            var app = CafeAI.create();
            var simpleCapture  = new CapturingMock("simple");
            var complexCapture = new CapturingMock("complex");

            // Original unnamed registration path — must not be broken
            app.ai(ModelRouter.smart()
                .simple(simpleCapture)
                .complex(complexCapture));

            var response = app.prompt("short").call();
            assertThat(response.text()).isEqualTo("simple");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider mockProvider(String modelId, String response) {
        return new SimpleMock(modelId, response);
    }

    private static AiProvider visionMock(String response) {
        return new VisionMock(response);
    }

    // Simple mock — text calls only
    private static final class SimpleMock
            implements AiProvider,
                       io.cafeai.core.internal.LangchainBridge.ChatModelAccess {
        private final String modelId;
        private final String response;

        SimpleMock(String modelId, String response) {
            this.modelId  = modelId;
            this.response = response;
        }

        @Override public String       name()    { return "mock"; }
        @Override public String       modelId() { return modelId; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        @Override
        public dev.langchain4j.model.chat.ChatModel toChatModel() {
            String r = response;
            return new dev.langchain4j.model.chat.ChatModel() {
                @Override
                public dev.langchain4j.model.chat.response.ChatResponse doChat(
                        dev.langchain4j.model.chat.request.ChatRequest req) {
                    return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(r))
                        .tokenUsage(new dev.langchain4j.model.output.TokenUsage(5, 2))
                        .build();
                }
            };
        }
    }

    // Capturing mock — records whether it was called
    private static final class CapturingMock
            implements AiProvider,
                       io.cafeai.core.internal.LangchainBridge.ChatModelAccess {
        private final String response;
        volatile boolean wasCalled = false;

        CapturingMock(String response) { this.response = response; }

        @Override public String       name()    { return "capturing-mock"; }
        @Override public String       modelId() { return "mock-model"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        @Override
        public dev.langchain4j.model.chat.ChatModel toChatModel() {
            CapturingMock self = this;
            String r = response;
            return new dev.langchain4j.model.chat.ChatModel() {
                @Override
                public dev.langchain4j.model.chat.response.ChatResponse doChat(
                        dev.langchain4j.model.chat.request.ChatRequest req) {
                    self.wasCalled = true;
                    return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(r))
                        .tokenUsage(new dev.langchain4j.model.output.TokenUsage(5, 2))
                        .build();
                }
            };
        }
    }

    // Vision-capable mock
    private static final class VisionMock
            implements AiProvider,
                       io.cafeai.core.internal.LangchainBridge.ChatModelAccess {
        private final String response;

        VisionMock(String response) { this.response = response; }

        @Override public String       name()           { return "vision-mock"; }
        @Override public String       modelId()        { return "mock-vision"; }
        @Override public ProviderType type()           { return ProviderType.CUSTOM; }
        @Override public boolean      supportsVision() { return true; }

        @Override
        public dev.langchain4j.model.chat.ChatModel toChatModel() {
            String r = response;
            return new dev.langchain4j.model.chat.ChatModel() {
                @Override
                public dev.langchain4j.model.chat.response.ChatResponse doChat(
                        dev.langchain4j.model.chat.request.ChatRequest req) {
                    return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(r))
                        .tokenUsage(new dev.langchain4j.model.output.TokenUsage(5, 2))
                        .build();
                }
            };
        }
    }
}
