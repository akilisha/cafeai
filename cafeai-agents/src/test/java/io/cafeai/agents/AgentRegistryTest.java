package io.cafeai.agents;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.spi.AgentBridge;
import io.cafeai.core.spi.ObserveBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRegistryTest {

    interface Assistant {
        String chat(String message);
    }

    private FakeSupport support;
    private AgentRegistry registry;

    @BeforeEach
    void setUp() {
        support = new FakeSupport();
        registry = new AgentRegistry();
    }

    @Test
    void register_returnsConfig_andRejectsDuplicates() {
        registry.init(support);

        var config = registry.register("assistant", Assistant.class);
        assertThat(config).isNotNull();
        assertThat(registry.isRegistered("assistant")).isTrue();
        assertThat(registry.count()).isEqualTo(1);

        assertThatThrownBy(() -> registry.register("assistant", Assistant.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already registered");
    }

    @Test
    void resolve_beforeInit_fails() {
        registry.register("assistant", Assistant.class);

        assertThatThrownBy(() -> registry.resolve("assistant", Assistant.class, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not initialised");
    }

    @Test
    void resolve_unknownAgent_fails() {
        registry.init(support);

        assertThatThrownBy(() -> registry.resolve("ghost", Assistant.class, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No agent registered");
    }

    @Test
    void resolve_buildsWorkingAiService() {
        registry.init(support);
        support.model = fixedModel("pong");
        registry.register("assistant", Assistant.class);

        Assistant agent = registry.resolve("assistant", Assistant.class, null);
        assertThat(agent.chat("ping")).isEqualTo("pong");
        assertThat(support.model.lastUserText()).isEqualTo("ping");
    }

    @Test
    void systemPrompt_isPassedToModel() {
        registry.init(support);
        support.model = fixedModel("ok");
        registry.register("assistant", Assistant.class).system("You are a pirate.");

        Assistant agent = registry.resolve("assistant", Assistant.class, null);
        agent.chat("hello");

        assertThat(support.model.lastSystemText()).isEqualTo("You are a pirate.");
    }

    @Test
    void statelessResolve_cachesTheProxy() {
        registry.init(support);
        support.model = fixedModel("ok");
        registry.register("assistant", Assistant.class);

        Assistant a = registry.resolve("assistant", Assistant.class, null);
        Assistant b = registry.resolve("assistant", Assistant.class, null);
        assertThat(a).isSameAs(b);
    }

    @Test
    void agentModelOverride_winsOverDefault() {
        registry.init(support);
        support.model = fixedModel("default");
        FixedModel override = fixedModel("override");
        AiProvider overrideProvider = namedProvider("override");
        support.overrides.put(overrideProvider, override);

        registry.register("assistant", Assistant.class).model(overrideProvider);
        Assistant agent = registry.resolve("assistant", Assistant.class, null);

        assertThat(agent.chat("x")).isEqualTo("override");
    }

    @Test
    void sessionMemory_isThreadedThroughTheMemoryStrategy() {
        registry.init(support);
        support.model = fixedModel("hi there");
        MemoryStrategy memory = MemoryStrategy.inMemory();
        registry.register("assistant", Assistant.class).memory(memory);

        Assistant agent = registry.resolve("assistant", Assistant.class, "session-1");
        agent.chat("hello");

        var ctx = memory.retrieve("session-1");
        assertThat(ctx).isNotNull();
        assertThat(ctx.messages()).extracting(m -> m.content())
            .contains("hello", "hi there");
    }

    @Test
    void sessionMemory_keepsSessionsSeparate() {
        registry.init(support);
        support.model = fixedModel("reply");
        MemoryStrategy memory = MemoryStrategy.inMemory();
        registry.register("assistant", Assistant.class).memory(memory);

        registry.resolve("assistant", Assistant.class, "a").chat("from-a");
        registry.resolve("assistant", Assistant.class, "b").chat("from-b");

        assertThat(memory.retrieve("a").messages()).extracting(m -> m.content()).contains("from-a");
        assertThat(memory.retrieve("a").messages()).extracting(m -> m.content()).doesNotContain("from-b");
        assertThat(memory.retrieve("b").messages()).extracting(m -> m.content()).contains("from-b");
    }

    @Test
    void outputGuardrail_blocksAViolatingResponse() {
        registry.init(support);
        support.model = fixedModel("here is a SECRET leak");
        registry.register("assistant", Assistant.class)
            .guard(blockIfContains("SECRET", GuardRail.Position.POST_LLM));

        Assistant agent = registry.resolve("assistant", Assistant.class, null);
        assertThatThrownBy(() -> agent.chat("tell me"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void outputGuardrail_allowsACleanResponse() {
        registry.init(support);
        support.model = fixedModel("all good here");
        registry.register("assistant", Assistant.class)
            .guard(blockIfContains("SECRET", GuardRail.Position.POST_LLM));

        Assistant agent = registry.resolve("assistant", Assistant.class, null);
        assertThat(agent.chat("tell me")).isEqualTo("all good here");
    }

    @Test
    void mcpToolSource_failsWithAHelpfulMessageUntilTheConnectorLands() {
        registry.init(support);
        support.model = fixedModel("ok");
        registry.register("assistant", Assistant.class).mcp("some-server");

        assertThatThrownBy(() -> registry.resolve("assistant", Assistant.class, null))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("McpEndpoint");
    }

    @Test
    void observeBridge_bracketsTheInvocation() {
        RecordingObserveBridge bridge = new RecordingObserveBridge();
        support.observeBridge = bridge;
        registry.init(support);
        support.model = fixedModel("done");
        registry.register("assistant", Assistant.class);

        registry.resolve("assistant", Assistant.class, null).chat("go");

        assertThat(bridge.before).containsExactly("assistant");
        assertThat(bridge.afterOk).containsExactly("assistant");
        assertThat(bridge.afterErr).isEmpty();
    }

    @Test
    void rag_wiringDegradesGracefullyWithoutCafeaiRag() {
        // retriever + store + model all present, but no RagPipeline on the test
        // classpath — the content retriever must return nothing, not throw.
        support.ragRetriever = "retriever-handle";
        support.vectorStore = "store-handle";
        support.embeddingModel = "model-handle";
        registry.init(support);
        support.model = fixedModel("answer");
        registry.register("assistant", Assistant.class);

        Assistant agent = registry.resolve("assistant", Assistant.class, null);
        assertThat(agent.chat("q")).isEqualTo("answer");
    }

    // ── fakes ────────────────────────────────────────────────────────────────

    private static final class FakeSupport implements AgentBridge.AgentSupport {
        FixedModel model = fixedModel("");
        final AiProvider defaultProvider = namedProvider("default");
        final java.util.Map<AiProvider, FixedModel> overrides = new java.util.HashMap<>();
        ObserveBridge observeBridge;
        Object ragRetriever;
        Object vectorStore;
        Object embeddingModel;

        @Override
        public ChatModel chatModel(AiProvider provider) {
            FixedModel o = overrides.get(provider);
            return o != null ? o : model;
        }

        @Override public AiProvider defaultProvider() { return defaultProvider; }
        @Override public ObserveBridge observeBridge() { return observeBridge; }
        @Override public MemoryStrategy defaultMemory() { return null; }
        @Override public Object ragRetriever() { return ragRetriever; }
        @Override public Object vectorStore() { return vectorStore; }
        @Override public Object embeddingModel() { return embeddingModel; }
    }

    private static final class RecordingObserveBridge implements ObserveBridge {
        final List<String> before  = new java.util.ArrayList<>();
        final List<String> afterOk = new java.util.ArrayList<>();
        final List<String> afterErr = new java.util.ArrayList<>();

        @Override public void setStrategy(Object strategy) {}
        @Override public Object beforePrompt(io.cafeai.core.ai.PromptRequest r) { return null; }
        @Override public void afterPrompt(Object c, io.cafeai.core.ai.PromptRequest r,
                                          io.cafeai.core.ai.PromptResponse resp, Throwable e) {}

        @Override public Object beforeAgent(String agentName) {
            before.add(agentName);
            return "ctx:" + agentName;
        }

        @Override public void afterAgent(Object context, String agentName, Throwable error) {
            (error == null ? afterOk : afterErr).add(agentName);
        }
    }

    private static AiProvider namedProvider(String name) {
        return new AiProvider() {
            @Override public String name() { return name; }
            @Override public String modelId() { return name + "-model"; }
            @Override public ProviderType type() { return ProviderType.CUSTOM; }
        };
    }

    private static FixedModel fixedModel(String response) {
        return new FixedModel(response);
    }

    private static final class FixedModel implements ChatModel {
        private final String response;
        private final List<ChatMessage> received = new CopyOnWriteArrayList<>();

        FixedModel(String response) {
            this.response = response;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            received.clear();
            received.addAll(request.messages());
            return ChatResponse.builder()
                .aiMessage(AiMessage.from(response))
                .tokenUsage(new TokenUsage(5, 5))
                .build();
        }

        String lastUserText() {
            for (int i = received.size() - 1; i >= 0; i--) {
                if (received.get(i) instanceof UserMessage um) return um.singleText();
            }
            return null;
        }

        String lastSystemText() {
            for (ChatMessage m : received) {
                if (m instanceof SystemMessage sm) return sm.text();
            }
            return null;
        }
    }

    /** A minimal POST_LLM guardrail that flags any output containing {@code needle}. */
    private static GuardRail blockIfContains(String needle, GuardRail.Position position) {
        return new GuardRail() {
            @Override public String name() { return "block-if-contains:" + needle; }
            @Override public Position position() { return position; }
            @Override public Action action() { return Action.BLOCK; }

            @Override
            public OutputCheckResult checkOutput(String output) {
                return output != null && output.contains(needle)
                    ? OutputCheckResult.violation("output contains '" + needle + "'")
                    : OutputCheckResult.pass();
            }

            @Override
            public void handle(io.cafeai.core.routing.Request req,
                               io.cafeai.core.routing.Response res,
                               io.cafeai.core.middleware.Next next) {
                next.run();
            }
        };
    }
}
