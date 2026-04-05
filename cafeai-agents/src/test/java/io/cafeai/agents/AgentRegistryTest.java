package io.cafeai.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.AiProvider.ProviderType;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link AgentRegistry} — session isolation, guardrail blocking,
 * and agent resolution.
 *
 * <p>Uses a mock {@link ChatModel} so no real LLM calls are made.
 */
class AgentRegistryTest {

    // ── Fixture agent interface ────────────────────────────────────────────────

    interface EchoAgent {
        String echo(String message);
    }

    // ── Mock provider ──────────────────────────────────────────────────────────

    static final class MockProvider
            implements AiProvider, LangchainBridge.ChatModelAccess {

        private final String response;

        MockProvider(String response) { this.response = response; }

        @Override public String name()        { return "mock"; }
        @Override public String modelId()     { return "mock-model"; }
        @Override public ProviderType type()  { return ProviderType.CUSTOM; }

        @Override
        public ChatModel toChatModel() {
            String resp = response;
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.from(resp))
                        .tokenUsage(new TokenUsage(5, 5))
                        .build();
                }
            };
        }
    }

    // ── Fields ─────────────────────────────────────────────────────────────────

    private AgentRegistry registry;
    private MockProvider  provider;

    @BeforeEach
    void setUp() {
        registry = new AgentRegistry();
        provider = new MockProvider("Echo response");
    }

    // ── Registration tests ─────────────────────────────────────────────────────

    @Test
    @DisplayName("register() stores agent — isRegistered() returns true")
    void register_storesAgent() {
        var config = new AgentConfig<>(EchoAgent.class);
        registry.register("echo", config);
        assertThat(registry.isRegistered("echo")).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("register(null name) throws NullPointerException")
    void register_nullName_throws() {
        var config = new AgentConfig<>(EchoAgent.class);
        assertThatThrownBy(() -> registry.register(null, config))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("register(null config) throws NullPointerException")
    void register_nullConfig_throws() {
        assertThatThrownBy(() -> registry.register("echo", null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("isRegistered() returns false for unknown agent")
    void isRegistered_unknown_returnsFalse() {
        assertThat(registry.isRegistered("unknown")).isFalse();
    }

    // ── Resolution tests ───────────────────────────────────────────────────────

    @Test
    @DisplayName("resolve() unknown agent throws IllegalArgumentException with helpful message")
    void resolve_unknownAgent_throws() {
        assertThatThrownBy(() ->
            registry.resolve("unknown", EchoAgent.class, null, provider))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown")
            .hasMessageContaining("app.agent");
    }

    @Test
    @DisplayName("resolve() stateless agent returns non-null proxy")
    void resolve_stateless_returnsProxy() {
        var config = new AgentConfig<>(EchoAgent.class);
        registry.register("echo", config);

        EchoAgent agent = registry.resolve("echo", EchoAgent.class, null, provider);
        assertThat(agent).isNotNull();
    }

    @Test
    @DisplayName("resolve() stateless agent returns same instance on repeated calls")
    void resolve_stateless_returnsSameInstance() {
        var config = new AgentConfig<>(EchoAgent.class);
        registry.register("echo", config);

        EchoAgent a1 = registry.resolve("echo", EchoAgent.class, null, provider);
        EchoAgent a2 = registry.resolve("echo", EchoAgent.class, null, provider);
        assertThat(a1).isSameAs(a2);
    }

    @Test
    @DisplayName("resolve() with memory + same sessionId returns same instance")
    void resolve_withSession_sameId_sameInstance() {
        var config = new AgentConfig<>(EchoAgent.class)
            .memory(io.cafeai.core.memory.MemoryStrategy.inMemory());
        registry.register("echo", config);

        EchoAgent a1 = registry.resolve("echo", EchoAgent.class, "session-1", provider);
        EchoAgent a2 = registry.resolve("echo", EchoAgent.class, "session-1", provider);
        assertThat(a1).isSameAs(a2);
    }

    @Test
    @DisplayName("resolve() with memory + different sessionIds returns different instances")
    void resolve_withSession_differentIds_differentInstances() {
        var config = new AgentConfig<>(EchoAgent.class)
            .memory(io.cafeai.core.memory.MemoryStrategy.inMemory());
        registry.register("echo", config);

        EchoAgent a1 = registry.resolve("echo", EchoAgent.class, "session-1", provider);
        EchoAgent a2 = registry.resolve("echo", EchoAgent.class, "session-2", provider);
        assertThat(a1).isNotSameAs(a2);
    }

    // ── Guardrail tests ────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkGuardrails() passes for agent with no guardrails")
    void checkGuardrails_noGuardrails_passes() {
        var config = new AgentConfig<>(EchoAgent.class);
        registry.register("echo", config);

        assertThatNoException()
            .isThrownBy(() -> registry.checkGuardrails("echo", "hello"));
    }

    @Test
    @DisplayName("checkGuardrails() on unknown agent is a no-op")
    void checkGuardrails_unknownAgent_noOp() {
        assertThatNoException()
            .isThrownBy(() -> registry.checkGuardrails("unknown", "hello"));
    }

    // ── AgentConfig escape hatch ───────────────────────────────────────────────

    @Test
    @DisplayName("AgentConfig.configure() stores consumer")
    void agentConfig_configure_storesConsumer() {
        var config = new AgentConfig<>(EchoAgent.class)
            .configure(builder -> {
                // escape hatch — would configure advanced options in real use
            });

        assertThat(config.builderConsumer()).isNotNull();
    }

    @Test
    @DisplayName("AgentConfig.system() sets system prompt")
    void agentConfig_system_setsPrompt() {
        var config = new AgentConfig<>(EchoAgent.class)
            .system("You are a helpful assistant.");
        assertThat(config.systemPrompt()).isEqualTo("You are a helpful assistant.");
    }

    @Test
    @DisplayName("AgentConfig.guard() adds guardrails")
    void agentConfig_guard_addsRails() {
        var rail = io.cafeai.core.guardrails.GuardRail.jailbreak();
        var config = new AgentConfig<>(EchoAgent.class).guard(rail);
        assertThat(config.guardRails()).hasSize(1);
    }
}
