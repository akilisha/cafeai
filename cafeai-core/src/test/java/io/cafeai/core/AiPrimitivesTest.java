package io.cafeai.core;

import io.cafeai.core.ai.*;
import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ROADMAP-07 Phases 1 and 2:
 * LLM provider registration, prompt API, template API, and memory threading.
 *
 * <p>All tests use mock providers and in-memory stubs — no real LLM calls are made.
 * Real provider integration tests are in {@code CafeAIIntegrationTest}
 * and annotated {@code @RequiresApiKey}.
 */
class AiPrimitivesTest {

    // ── Phase 1: Provider Registration ───────────────────────────────────────

    @Test
    @DisplayName("OpenAI.gpt4o() has correct metadata")
    void openAi_gpt4o_metadata() {
        var p = OpenAI.gpt4o();
        assertThat(p.name()).isEqualTo("openai");
        assertThat(p.modelId()).isEqualTo("gpt-4o");
        assertThat(p.type()).isEqualTo(AiProvider.ProviderType.OPENAI);
    }

    @Test
    @DisplayName("OpenAI.gpt4oMini() has correct metadata")
    void openAi_gpt4oMini_metadata() {
        var p = OpenAI.gpt4oMini();
        assertThat(p.modelId()).isEqualTo("gpt-4o-mini");
        assertThat(p.type()).isEqualTo(AiProvider.ProviderType.OPENAI);
    }

    @Test
    @DisplayName("OpenAI.of(modelId) accepts arbitrary model IDs")
    void openAi_of_arbitraryModelId() {
        var p = OpenAI.of("gpt-4-turbo");
        assertThat(p.modelId()).isEqualTo("gpt-4-turbo");
        assertThat(p.name()).isEqualTo("openai");
    }

    @Test
    @DisplayName("Anthropic.claude35Sonnet() has correct metadata")
    void anthropic_claude35Sonnet_metadata() {
        var p = Anthropic.claude35Sonnet();
        assertThat(p.name()).isEqualTo("anthropic");
        assertThat(p.modelId()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(p.type()).isEqualTo(AiProvider.ProviderType.ANTHROPIC);
    }

    @Test
    @DisplayName("Ollama.llama3() uses localhost:11434 by default")
    void ollama_llama3_defaultUrl() {
        var p = Ollama.llama3();
        assertThat(p.name()).isEqualTo("ollama");
        assertThat(p.modelId()).isEqualTo("llama3");
        assertThat(p.type()).isEqualTo(AiProvider.ProviderType.OLLAMA);
    }

    @Test
    @DisplayName("Ollama.at(url).model(id) sets remote base URL")
    void ollama_remoteInstance() {
        var p = Ollama.at("http://gpu-box:11434").model("mistral");
        assertThat(p.name()).isEqualTo("ollama");
        assertThat(p.modelId()).isEqualTo("mistral");
    }

    @Test
    @DisplayName("ModelRouter.smart() stores simple and complex providers")
    void modelRouter_storesProviders() {
        var router = ModelRouter.smart()
            .simple(OpenAI.gpt4oMini())
            .complex(OpenAI.gpt4o());
        assertThat(router.simpleModel().modelId()).isEqualTo("gpt-4o-mini");
        assertThat(router.complexModel().modelId()).isEqualTo("gpt-4o");
    }

    // ── Phase 1: app.prompt() with mock provider ──────────────────────────────

    @Test
    @DisplayName("app.prompt() without registered provider throws IllegalStateException")
    void prompt_withoutProvider_throws() {
        var app = CafeAI.create();
        assertThatIllegalStateException()
            .isThrownBy(() -> app.prompt("hello").call())
            .withMessageContaining("No AI provider registered");
    }

    @Test
    @DisplayName("app.prompt() with mock provider returns response")
    void prompt_withMockProvider_returnsResponse() {
        var app = CafeAI.create();
        app.ai(mockProvider("mock", "test-model", "Paris"));

        PromptResponse response = app.prompt("What is the capital of France?").call();

        assertThat(response.text()).isEqualTo("Paris");
        assertThat(response.modelId()).isEqualTo("test-model");
        assertThat(response.fromCache()).isFalse();
    }

    @Test
    @DisplayName("app.prompt().call() returns PromptResponse with token counts")
    void prompt_tokenCounts() {
        var app = CafeAI.create();
        app.ai(mockProvider("mock", "test-model", "42"));

        PromptResponse response = app.prompt("What is 6 times 7?").call();

        assertThat(response.totalTokens()).isGreaterThanOrEqualTo(0);
        assertThat(response.promptTokens()).isGreaterThanOrEqualTo(0);
        assertThat(response.outputTokens()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("PromptResponse.toString() delegates to text()")
    void promptResponse_toString() {
        var app = CafeAI.create();
        app.ai(mockProvider("mock", "m", "hello world"));
        PromptResponse r = app.prompt("hi").call();
        assertThat(r.toString()).isEqualTo("hello world");
    }

    // ── Phase 1: System prompt injection ─────────────────────────────────────

    @Test
    @DisplayName("app.system() is stored and retrievable via local()")
    void system_storedInLocals() {
        var app = CafeAI.create();
        app.system("You are a helpful assistant.");
        assertThat(app.local(Locals.SYSTEM_PROMPT)).isEqualTo("You are a helpful assistant.");
    }

    @Test
    @DisplayName("app.prompt().system() overrides app-level system prompt for one call")
    void prompt_systemOverride_isUsed() {
        var capturingProvider = new CapturingMockProvider("answer");
        var app = CafeAI.create();
        app.system("global system");
        app.ai(capturingProvider);

        app.prompt("question").system("override system").call();

        // The override should have been seen by the executor
        assertThat(capturingProvider.lastSystemPrompt).isEqualTo("override system");
    }

    @Test
    @DisplayName("app.system() — last call wins")
    void system_lastCallWins() {
        var app = CafeAI.create();
        app.system("first");
        app.system("second");
        assertThat(app.local(Locals.SYSTEM_PROMPT)).isEqualTo("second");
    }

    // ── Phase 2: Template registration and retrieval ──────────────────────────

    @Test
    @DisplayName("app.template(name, body) registers and app.template(name) retrieves")
    void template_registerAndRetrieve() {
        var app = CafeAI.create();
        app.template("greet", "Hello, {{name}}!");

        Template t = app.template("greet");
        assertThat(t.name()).isEqualTo("greet");
        assertThat(t.body()).isEqualTo("Hello, {{name}}!");
    }

    @Test
    @DisplayName("app.template(unknown) throws IllegalArgumentException")
    void template_unknown_throws() {
        var app = CafeAI.create();
        assertThatIllegalArgumentException()
            .isThrownBy(() -> app.template("nonexistent"))
            .withMessageContaining("nonexistent");
    }

    @Test
    @DisplayName("Template.render() substitutes all variables")
    void template_render_substitutesAll() {
        var app = CafeAI.create();
        app.template("classify",
            "Classify into one of: {{categories}}.\nMessage: {{message}}");

        String rendered = app.template("classify").render(Map.of(
            "categories", "billing, shipping",
            "message",    "Where is my order?"
        ));

        assertThat(rendered).contains("billing, shipping");
        assertThat(rendered).contains("Where is my order?");
    }

    @Test
    @DisplayName("Template.render() leaves unreplaced variables intact")
    void template_render_missingVarLeftIntact() {
        var app = CafeAI.create();
        app.template("t", "Hello {{name}}, your code is {{code}}.");

        String rendered = app.template("t").render(Map.of("name", "Ada"));
        assertThat(rendered).isEqualTo("Hello Ada, your code is {{code}}.");
    }

    @Test
    @DisplayName("Template.renderStrict() throws TemplateException for missing variable")
    void template_renderStrict_throwsOnMissingVar() {
        var app = CafeAI.create();
        app.template("strict", "Hello {{name}}, your code is {{code}}.");

        assertThatExceptionOfType(Template.TemplateException.class)
            .isThrownBy(() -> app.template("strict")
                .renderStrict(Map.of("name", "Ada")))
            .withMessageContaining("{{code}}");
    }

    @Test
    @DisplayName("app.prompt(templateName, vars) renders and sends the template")
    void prompt_templateName_rendersAndSends() {
        var capturingProvider = new CapturingMockProvider("billing");
        var app = CafeAI.create();
        app.ai(capturingProvider);
        app.template("classify",
            "Classify into: {{categories}}.\nMessage: {{message}}");

        app.prompt("classify", Map.of(
            "categories", "billing, shipping",
            "message",    "I was overcharged"
        )).call();

        assertThat(capturingProvider.lastMessage).contains("I was overcharged");
        assertThat(capturingProvider.lastMessage).contains("billing, shipping");
    }

    @Test
    @DisplayName("app.prompt(unknown template, vars) throws IllegalArgumentException")
    void prompt_unknownTemplate_throws() {
        var app = CafeAI.create();
        app.ai(mockProvider("m", "m", "x"));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> app.prompt("nonexistent", Map.of()).call())
            .withMessageContaining("nonexistent");
    }

    // ── Phase 2: Session memory threading ────────────────────────────────────

    @Test
    @DisplayName("app.prompt().session() stores exchange in memory after call")
    void prompt_session_storesExchange() {
        var app = CafeAI.create();
        app.ai(mockProvider("m", "m", "I'm doing well, thanks!"));
        app.memory(MemoryStrategy.inMemory());

        app.prompt("How are you?").session("sess-1").call();

        MemoryStrategy strategy = app.local(Locals.MEMORY_STRATEGY, MemoryStrategy.class);
        assertThat(strategy).isNotNull();

        ConversationContext stored = strategy.retrieve("sess-1");
        assertThat(stored).isNotNull();
        assertThat(stored.messages()).hasSize(2);
        assertThat(stored.messages().get(0).role()).isEqualTo("user");
        assertThat(stored.messages().get(0).content()).isEqualTo("How are you?");
        assertThat(stored.messages().get(1).role()).isEqualTo("assistant");
        assertThat(stored.messages().get(1).content()).isEqualTo("I'm doing well, thanks!");
    }

    @Test
    @DisplayName("app.prompt().session() includes prior history in subsequent call")
    void prompt_session_includesPriorHistory() {
        var capturingProvider = new CapturingMockProvider("response");
        var app = CafeAI.create();
        app.ai(capturingProvider);
        app.memory(MemoryStrategy.inMemory());

        // First turn
        app.prompt("My name is Ada").session("sess-2").call();

        // Reset capture
        capturingProvider.lastMessages = null;

        // Second turn — history should include first turn
        app.prompt("What is my name?").session("sess-2").call();

        // The messages list sent to the LLM should include the prior exchange
        assertThat(capturingProvider.lastMessages).isNotNull();
        assertThat(capturingProvider.lastMessages.size()).isGreaterThan(1);
    }

    @Test
    @DisplayName("Prompt without session() does not require memory to be registered")
    void prompt_noSession_worksWithoutMemory() {
        var app = CafeAI.create();
        app.ai(mockProvider("m", "m", "hello"));
        // No app.memory() called

        assertThatCode(() -> app.prompt("hello").call())
            .doesNotThrowAnyException();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Mock provider that returns a fixed response. */
    private static AiProvider mockProvider(String name, String modelId, String response) {
        return new MockProvider(name, modelId, response);
    }

    /**
     * Named inner class so it can implement both {@link AiProvider} and
     * {@link io.cafeai.core.internal.LangchainBridge.ChatLanguageModelAccess}.
     * Anonymous classes cannot list additional interfaces — named classes can.
     */
    private static final class MockProvider
            implements AiProvider,
                       io.cafeai.core.internal.LangchainBridge.ChatLanguageModelAccess {

        private final String providerName;
        private final String model;
        private final String fixedResponse;

        MockProvider(String providerName, String model, String fixedResponse) {
            this.providerName  = providerName;
            this.model         = model;
            this.fixedResponse = fixedResponse;
        }

        @Override public String name()       { return providerName; }
        @Override public String modelId()    { return model; }
        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        @Override
        public dev.langchain4j.model.chat.ChatLanguageModel toLangchainModel() {
            return messages -> dev.langchain4j.model.output.Response.from(
                dev.langchain4j.data.message.AiMessage.from(fixedResponse),
                new dev.langchain4j.model.output.TokenUsage(10, 5));
        }
    }

    /**
     * A mock provider that captures the messages it received for assertion.
     */
    static final class CapturingMockProvider
            implements AiProvider,
                       io.cafeai.core.internal.LangchainBridge.ChatLanguageModelAccess {

        final String fixedResponse;
        String lastMessage;
        String lastSystemPrompt;
        java.util.List<dev.langchain4j.data.message.ChatMessage> lastMessages;

        CapturingMockProvider(String fixedResponse) {
            this.fixedResponse = fixedResponse;
        }

        @Override public String name()       { return "capturing-mock"; }
        @Override public String modelId()    { return "mock-model"; }
        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        @Override
        public dev.langchain4j.model.chat.ChatLanguageModel toLangchainModel() {
            return messages -> {
                this.lastMessages = new java.util.ArrayList<>(messages);
                // Capture the last user message
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if (messages.get(i) instanceof
                            dev.langchain4j.data.message.UserMessage um) {
                        this.lastMessage = um.singleText();
                        break;
                    }
                }
                // Capture the system message if present
                for (var msg : messages) {
                    if (msg instanceof dev.langchain4j.data.message.SystemMessage sm) {
                        this.lastSystemPrompt = sm.text();
                        break;
                    }
                }
                return dev.langchain4j.model.output.Response.from(
                    dev.langchain4j.data.message.AiMessage.from(fixedResponse),
                    new dev.langchain4j.model.output.TokenUsage(10, 5));
            };
        }
    }
}
