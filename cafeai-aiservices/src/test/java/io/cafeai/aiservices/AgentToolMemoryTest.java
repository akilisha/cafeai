package io.cafeai.aiservices;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.cafeai.aiservices.adapter.CafeAiChatMemoryStore;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.memory.ConversationContext;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An agent that calls a tool while it has session memory. A tool call is a message with no text, and
 * its result is a message of its own; both have to survive the trip through the memory store, or the
 * model is sent a call with no result. A live model found this: the second message of an agent
 * with tools and memory crashed with {@code text cannot be null}.
 */
@DisplayName("agent tools with session memory")
class AgentToolMemoryTest {

    interface Vault {
        String ask(String question);
    }

    public static class VaultTools {
        final AtomicInteger calls = new AtomicInteger();

        @Tool("Returns the vault access code.")
        public String accessCode(@P("why the code is wanted") String reason) {
            calls.incrementAndGet();
            return "K7-ALPHA-93";
        }
    }

    /**
     * Asks for the tool the first time it sees a question, and once it has the tool's result answers
     * with it. Records every request it is sent.
     */
    private static final class ScriptedModel implements AiProvider, LangchainBridge.ChatModelAccess {
        final List<List<ChatMessage>> requests = new CopyOnWriteArrayList<>();

        @Override public String       name()    { return "scripted"; }
        @Override public String       modelId() { return "scripted"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) {
                    requests.add(List.copyOf(r.messages()));
                    ChatMessage last = r.messages().get(r.messages().size() - 1);
                    if (last instanceof ToolExecutionResultMessage result) {
                        return reply(AiMessage.from("The code is " + result.text()));
                    }
                    return reply(AiMessage.from(ToolExecutionRequest.builder()
                        .id("call-" + requests.size()).name("accessCode")
                        .arguments("{\"reason\":\"asked\"}").build()));
                }
            };
        }

        private static ChatResponse reply(AiMessage message) {
            return ChatResponse.builder().aiMessage(message).build();
        }
    }

    @Test @DisplayName("a tool call and its result go through the memory store and come back as they were")
    void storeRoundTripsToolMessages() {
        var memory = MemoryStrategy.inMemory();
        var store = new CafeAiChatMemoryStore(memory);
        List<ChatMessage> messages = new ArrayList<>(List.of(
            UserMessage.from("What is the code?"),
            AiMessage.from(ToolExecutionRequest.builder().id("c1").name("accessCode").arguments("{}").build()),
            ToolExecutionResultMessage.from("c1", "accessCode", "K7-ALPHA-93"),
            AiMessage.from("The code is K7-ALPHA-93")));

        store.updateMessages("s", messages);
        List<ChatMessage> back = store.getMessages("s");

        assertThat(back).hasSize(4);
        assertThat(((AiMessage) back.get(1)).toolExecutionRequests()).extracting(ToolExecutionRequest::name)
            .containsExactly("accessCode");
        assertThat(((ToolExecutionResultMessage) back.get(2)).text()).isEqualTo("K7-ALPHA-93");
        assertThat(((AiMessage) back.get(3)).text()).isEqualTo("The code is K7-ALPHA-93");
    }

    @Test @DisplayName("plain user and assistant turns are still stored as readable text under their own roles")
    void plainTurnsStayText() {
        var memory = MemoryStrategy.inMemory();
        new CafeAiChatMemoryStore(memory).updateMessages("s",
            List.of(UserMessage.from("hello"), AiMessage.from("hi there")));

        assertThat(memory.retrieve("s").messages())
            .extracting(ConversationContext.Message::role, ConversationContext.Message::content)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("user", "hello"),
                             org.assertj.core.groups.Tuple.tuple("assistant", "hi there"));
    }

    @Test @DisplayName("an agent with a tool and session memory completes the tool loop, and the next turn still works")
    void agentLoopWithMemory() {
        var model = new ScriptedModel();
        var app = CafeAI.create();
        app.ai(model);
        app.memory(MemoryStrategy.inMemory());
        var tools = new VaultTools();
        app.agent("vault", Vault.class).tool(tools);

        String first = app.agent("vault", Vault.class, "s1").ask("What is the vault access code?");
        String second = app.agent("vault", Vault.class, "s1").ask("Tell me the code again.");

        assertThat(first).isEqualTo("The code is K7-ALPHA-93");
        assertThat(second).isEqualTo("The code is K7-ALPHA-93");
        assertThat(tools.calls).as("the tool ran once per question").hasValue(2);

        // The model was sent the tool's result in the same turn it asked for the tool
        assertThat(model.requests.get(1).get(model.requests.get(1).size() - 1))
            .isInstanceOf(ToolExecutionResultMessage.class);
        // and on the second question it was sent the first exchange, tool call and result included
        assertThat(model.requests.get(2)).anyMatch(m -> m instanceof ToolExecutionResultMessage);
    }
}
