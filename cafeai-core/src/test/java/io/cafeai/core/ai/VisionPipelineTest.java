package io.cafeai.core.ai;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ROADMAP-14 vision pipeline:
 * VisionRequest, VisionResponse, VisionMessageBuilder,
 * SchemaHintBuilder, ResponseDeserializer, AiProvider.supportsVision(),
 * TokenBudget, and RetryPolicy.
 *
 * <p>No real LLM calls. Mock providers only.
 */
class VisionPipelineTest {

    // ── AiProvider.supportsVision() ───────────────────────────────────────────

    @Nested
    @DisplayName("AiProvider.supportsVision()")
    class SupportsVision {

        @Test
        @DisplayName("OpenAI.gpt4o() supports vision")
        void gpt4o_supportsVision() {
            assertThat(OpenAI.gpt4o().supportsVision()).isTrue();
        }

        @Test
        @DisplayName("OpenAI.gpt4oMini() does not support vision")
        void gpt4oMini_doesNotSupportVision() {
            assertThat(OpenAI.gpt4oMini().supportsVision()).isFalse();
        }

        @Test
        @DisplayName("OpenAI.o1() does not support vision")
        void o1_doesNotSupportVision() {
            assertThat(OpenAI.o1().supportsVision()).isFalse();
        }

        @Test
        @DisplayName("Ollama.llava() supports vision")
        void llava_supportsVision() {
            assertThat(Ollama.llava().supportsVision()).isTrue();
        }

        @Test
        @DisplayName("Ollama.llama3() does not support vision")
        void llama3_doesNotSupportVision() {
            assertThat(Ollama.llama3().supportsVision()).isFalse();
        }

        @Test
        @DisplayName("AiProvider default supportsVision() returns false")
        void default_supportsVision_returnsFalse() {
            AiProvider custom = new AiProvider() {
                @Override public String name()       { return "custom"; }
                @Override public String modelId()    { return "my-model"; }
                @Override public ProviderType type() { return ProviderType.CUSTOM; }
            };
            assertThat(custom.supportsVision()).isFalse();
        }
    }

    // ── VisionRequest validation ──────────────────────────────────────────────

    @Nested
    @DisplayName("VisionRequest validation")
    class VisionRequestValidation {

        private final VisionRequest.VisionExecutor noopExecutor =
            req -> VisionResponse.builder().text("ok").modelId("mock").build();

        @Test
        @DisplayName("null prompt throws IllegalArgumentException")
        void nullPrompt_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VisionRequest(null, new byte[]{1}, "image/jpeg", noopExecutor))
                .withMessageContaining("prompt");
        }

        @Test
        @DisplayName("blank prompt throws IllegalArgumentException")
        void blankPrompt_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VisionRequest("  ", new byte[]{1}, "image/jpeg", noopExecutor))
                .withMessageContaining("prompt");
        }

        @Test
        @DisplayName("null content throws IllegalArgumentException")
        void nullContent_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VisionRequest("prompt", null, "image/jpeg", noopExecutor))
                .withMessageContaining("content");
        }

        @Test
        @DisplayName("empty content throws IllegalArgumentException")
        void emptyContent_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VisionRequest("prompt", new byte[0], "image/jpeg", noopExecutor))
                .withMessageContaining("content");
        }

        @Test
        @DisplayName("null mimeType throws IllegalArgumentException")
        void nullMimeType_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VisionRequest("prompt", new byte[]{1}, null, noopExecutor))
                .withMessageContaining("mimeType");
        }

        @Test
        @DisplayName("blank mimeType throws IllegalArgumentException")
        void blankMimeType_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new VisionRequest("prompt", new byte[]{1}, "", noopExecutor))
                .withMessageContaining("mimeType");
        }

        @Test
        @DisplayName("VisionRequest is fluent -- session(), system(), request() return this")
        void visionRequest_isFluent() {
            var req = new VisionRequest("p", new byte[]{1}, "image/jpeg", noopExecutor);
            assertThat(req.session("s")).isSameAs(req);
            assertThat(req.system("sys")).isSameAs(req);
        }

        @Test
        @DisplayName("VisionRequest stores fields correctly")
        void visionRequest_storesFields() {
            byte[] content = {1, 2, 3};
            var req = new VisionRequest("classify this", content, "application/pdf", noopExecutor)
                .session("sess-1")
                .system("You are an AP assistant.");

            assertThat(req.prompt()).isEqualTo("classify this");
            assertThat(req.content()).isEqualTo(content);
            assertThat(req.mimeType()).isEqualTo("application/pdf");
            assertThat(req.sessionId()).isEqualTo("sess-1");
            assertThat(req.systemOverride()).isEqualTo("You are an AP assistant.");
        }
    }

    // ── VisionResponse ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("VisionResponse")
    class VisionResponseTests {

        @Test
        @DisplayName("totalTokens() sums prompt and output tokens")
        void totalTokens_sumsCorrectly() {
            var r = VisionResponse.builder()
                .text("yes")
                .promptTokens(100)
                .outputTokens(25)
                .modelId("gpt-4o")
                .build();
            assertThat(r.totalTokens()).isEqualTo(125);
        }

        @Test
        @DisplayName("fromCache() always returns false")
        void fromCache_alwaysFalse() {
            var r = VisionResponse.builder().text("x").modelId("m").build();
            assertThat(r.fromCache()).isFalse();
        }

        @Test
        @DisplayName("ragDocuments() always returns empty list")
        void ragDocuments_alwaysEmpty() {
            var r = VisionResponse.builder().text("x").modelId("m").build();
            assertThat(r.ragDocuments()).isEmpty();
        }

        @Test
        @DisplayName("toString() delegates to text()")
        void toString_delegatesToText() {
            var r = VisionResponse.builder().text("invoice confirmed").modelId("m").build();
            assertThat(r.toString()).isEqualTo("invoice confirmed");
        }
    }

    // ── VisionNotSupportedException ───────────────────────────────────────────

    @Nested
    @DisplayName("VisionNotSupportedException")
    class VisionNotSupported {

        @Test
        @DisplayName("app.vision() with non-vision provider throws VisionNotSupportedException")
        void nonVisionProvider_throwsVisionNotSupportedException() {
            var app = CafeAI.create();
            app.ai(OpenAI.gpt4oMini());  // does not support vision

            assertThatThrownBy(() ->
                app.vision("classify", new byte[]{1}, "application/pdf").call())
                .isInstanceOf(VisionRequest.VisionNotSupportedException.class)
                .hasMessageContaining("gpt-4o-mini")
                .hasMessageContaining("vision");
        }

        @Test
        @DisplayName("app.vision() with vision-capable mock provider does not throw VisionNotSupportedException")
        void visionProvider_doesNotThrowVisionNotSupportedException() {
            var app = CafeAI.create();
            app.ai(visionMockProvider("yes, invoice"));

            // Vision-capable mock succeeds -- no exception thrown
            assertThatNoException().isThrownBy(() ->
                app.vision("classify", new byte[]{1, 2, 3}, "application/pdf").call());
        }
    }

    // ── SchemaHintBuilder ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("SchemaHintBuilder")
    class SchemaHintBuilderTests {

        record SimpleResult(String label, boolean flag, int count) {}
        record WithList(String name, List<String> tags) {}
        enum Status { APPROVED, QUERIED, REJECTED }
        record WithEnum(String vendor, Status status) {}

        @Test
        @DisplayName("generates JSON hint from Java record")
        void record_generatesHint() {
            String hint = SchemaHintBuilder.build(SimpleResult.class);
            assertThat(hint)
                .startsWith("{")
                .endsWith("}")
                .contains("\"label\"")
                .contains("\"flag\"")
                .contains("\"count\"");
        }

        @Test
        @DisplayName("boolean field generates false example value")
        void boolean_generatesFalse() {
            String hint = SchemaHintBuilder.build(SimpleResult.class);
            assertThat(hint).contains("false");
        }

        @Test
        @DisplayName("List field generates array example")
        void list_generatesArray() {
            String hint = SchemaHintBuilder.build(WithList.class);
            assertThat(hint).contains("[");
        }

        @Test
        @DisplayName("enum field generates pipe-separated values")
        void enum_generatesPipeSeparated() {
            String hint = SchemaHintBuilder.build(WithEnum.class);
            assertThat(hint).contains("APPROVED|QUERIED|REJECTED");
        }

        @Test
        @DisplayName("instruction() wraps hint with structured output directive")
        void instruction_wrapsHint() {
            String hint = SchemaHintBuilder.build(SimpleResult.class);
            String instruction = SchemaHintBuilder.instruction(SimpleResult.class, hint);
            assertThat(instruction)
                .contains("JSON")
                .contains(hint);
        }
    }

    // ── ResponseDeserializer ──────────────────────────────────────────────────

    @Nested
    @DisplayName("ResponseDeserializer")
    class ResponseDeserializerTests {

        record Point(String x, String y) {}

        @Test
        @DisplayName("strips ```json fences")
        void stripsJsonFence() {
            String raw = "```json\n{\"x\":\"1\",\"y\":\"2\"}\n```";
            assertThat(ResponseDeserializer.strip(raw)).isEqualTo("{\"x\":\"1\",\"y\":\"2\"}");
        }

        @Test
        @DisplayName("strips plain ``` fences")
        void stripsPlainFence() {
            String raw = "```\n{\"x\":\"1\",\"y\":\"2\"}\n```";
            assertThat(ResponseDeserializer.strip(raw)).isEqualTo("{\"x\":\"1\",\"y\":\"2\"}");
        }

        @Test
        @DisplayName("passes clean JSON through unchanged")
        void cleanJson_passesThrough() {
            String raw = "{\"x\":\"1\",\"y\":\"2\"}";
            assertThat(ResponseDeserializer.strip(raw)).isEqualTo(raw);
        }

        @Test
        @DisplayName("extracts JSON from prose surrounding it")
        void extractsJsonFromProse() {
            String raw = "Here is the result: {\"x\":\"1\",\"y\":\"2\"} Hope that helps!";
            assertThat(ResponseDeserializer.strip(raw)).isEqualTo("{\"x\":\"1\",\"y\":\"2\"}");
        }

        @Test
        @DisplayName("deserialises clean JSON to target type")
        void deserialisesToTargetType() {
            Point p = ResponseDeserializer.deserialise("{\"x\":\"3\",\"y\":\"4\"}", Point.class);
            assertThat(p.x()).isEqualTo("3");
            assertThat(p.y()).isEqualTo("4");
        }

        @Test
        @DisplayName("deserialises fenced JSON to target type")
        void deserialisesFromFencedJson() {
            Point p = ResponseDeserializer.deserialise(
                "```json\n{\"x\":\"5\",\"y\":\"6\"}\n```", Point.class);
            assertThat(p.x()).isEqualTo("5");
            assertThat(p.y()).isEqualTo("6");
        }

        @Test
        @DisplayName("throws StructuredOutputException on unparseable input")
        void throwsOnUnparseable() {
            assertThatThrownBy(() ->
                ResponseDeserializer.deserialise("not json at all", Point.class))
                .isInstanceOf(ResponseDeserializer.StructuredOutputException.class)
                .hasMessageContaining("Point");
        }
    }

    // ── TokenBudget ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TokenBudget")
    class TokenBudgetTests {

        @Test
        @DisplayName("perMinute(30_000) stores limit correctly")
        void perMinute_storesLimit() {
            var budget = TokenBudget.perMinute(30_000);
            assertThat(budget.tokensPerMinute()).isEqualTo(30_000);
            assertThat(budget.isUnlimited()).isFalse();
        }

        @Test
        @DisplayName("unlimited() has isUnlimited=true")
        void unlimited_isUnlimited() {
            var budget = TokenBudget.unlimited();
            assertThat(budget.isUnlimited()).isTrue();
        }

        @Test
        @DisplayName("perMinute(0) throws IllegalArgumentException")
        void perMinute_zero_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TokenBudget.perMinute(0));
        }

        @Test
        @DisplayName("perMinute(-1) throws IllegalArgumentException")
        void perMinute_negative_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> TokenBudget.perMinute(-1));
        }

        @Test
        @DisplayName("app.budget() registers budget and returns app for chaining")
        void app_budget_registers() {
            var app = CafeAI.create();
            assertThat(app.budget(TokenBudget.unlimited())).isSameAs(app);
        }
    }

    // ── RetryPolicy ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RetryPolicy")
    class RetryPolicyTests {

        @Test
        @DisplayName("onRateLimit() defaults: 3 attempts, 5s backoff")
        void onRateLimit_defaults() {
            var policy = RetryPolicy.onRateLimit();
            assertThat(policy.maxAttempts()).isEqualTo(3);
            assertThat(policy.backoff().toSeconds()).isEqualTo(5);
            assertThat(policy.retriesOnRateLimit()).isTrue();
        }

        @Test
        @DisplayName("maxAttempts() overrides the default")
        void maxAttempts_overrides() {
            var policy = RetryPolicy.onRateLimit().maxAttempts(5);
            assertThat(policy.maxAttempts()).isEqualTo(5);
        }

        @Test
        @DisplayName("backoff() overrides the default")
        void backoff_overrides() {
            var policy = RetryPolicy.onRateLimit().backoff(java.time.Duration.ofSeconds(10));
            assertThat(policy.backoff().toSeconds()).isEqualTo(10);
        }

        @Test
        @DisplayName("maxAttempts(0) throws IllegalArgumentException")
        void maxAttempts_zero_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> RetryPolicy.onRateLimit().maxAttempts(0));
        }

        @Test
        @DisplayName("backoff(null) throws IllegalArgumentException")
        void backoff_null_throws() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> RetryPolicy.onRateLimit().backoff(null));
        }

        @Test
        @DisplayName("app.retry() registers policy and returns app for chaining")
        void app_retry_registers() {
            var app = CafeAI.create();
            assertThat(app.retry(RetryPolicy.onRateLimit())).isSameAs(app);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * A mock provider that declares vision support, backed by a mock ChatModel.
     */
    private static AiProvider visionMockProvider(String response) {
        return new VisionMockProvider(response);
    }

    private static final class VisionMockProvider
            implements AiProvider,
                       io.cafeai.core.internal.LangchainBridge.ChatModelAccess {

        private final String fixedResponse;

        VisionMockProvider(String fixedResponse) {
            this.fixedResponse = fixedResponse;
        }

        @Override public String       name()          { return "vision-mock"; }
        @Override public String       modelId()       { return "mock-vision-model"; }
        @Override public ProviderType type()          { return ProviderType.CUSTOM; }
        @Override public boolean      supportsVision() { return true; }

        @Override
        public dev.langchain4j.model.chat.ChatModel toChatModel() {
            String resp = fixedResponse;
            return new dev.langchain4j.model.chat.ChatModel() {
                @Override
                public dev.langchain4j.model.chat.response.ChatResponse doChat(
                        dev.langchain4j.model.chat.request.ChatRequest request) {
                    return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(resp))
                        .tokenUsage(new dev.langchain4j.model.output.TokenUsage(50, 10))
                        .build();
                }
            };
        }
    }
}
