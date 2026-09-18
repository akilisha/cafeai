package io.cafeai.core.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.cafeai.core.CafeAI;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code .returning(Class)} declares the expected shape: it puts the type's JSON schema
 * instruction into the prompt. It used to store the type in a field nothing read, so
 * {@code returning(X).call()} sent no instruction at all.
 */
class StructuredOutputTest {

    record Verdict(String label, double confidence) {}

    @Test
    @DisplayName("returning(X).call() appends the schema instruction to the prompt")
    void returning_injectsSchemaInstruction() {
        var provider = new CapturingProvider("{\"label\":\"spam\",\"confidence\":0.9}");
        var app = CafeAI.create();
        app.ai(provider);

        String text = app.prompt("Classify this email").returning(Verdict.class).call().text();

        String instruction = SchemaHintBuilder.instruction(Verdict.class, SchemaHintBuilder.build(Verdict.class));
        assertThat(provider.lastUserText()).startsWith("Classify this email").endsWith(instruction);
        assertThat(text).contains("spam");   // raw JSON text comes back
    }

    @Test
    @DisplayName("call() without returning() sends the prompt untouched")
    void plainCall_sendsNoInstruction() {
        var provider = new CapturingProvider("hello");
        var app = CafeAI.create();
        app.ai(provider);

        app.prompt("Say hello").call();

        assertThat(provider.lastUserText()).isEqualTo("Say hello");
    }

    @Test
    @DisplayName("call(X) still deserialises without a prior returning(X)")
    void callWithType_needsNoReturning() {
        var provider = new CapturingProvider("{\"label\":\"ham\",\"confidence\":0.75}");
        var app = CafeAI.create();
        app.ai(provider);

        Verdict v = app.prompt("Classify this email").call(Verdict.class);

        assertThat(v).isEqualTo(new Verdict("ham", 0.75));
        assertThat(provider.lastUserText()).contains("JSON");
    }

    @Test
    @DisplayName("vision: returning(X).call() also appends the schema instruction")
    void vision_returning_injectsSchemaInstruction() {
        var provider = new CapturingProvider("{\"label\":\"cat\",\"confidence\":1.0}");
        var app = CafeAI.create();
        app.ai(provider);

        app.vision("What is this?", new byte[]{1, 2, 3}, "image/png")
           .returning(Verdict.class).call();

        String instruction = SchemaHintBuilder.instruction(Verdict.class, SchemaHintBuilder.build(Verdict.class));
        assertThat(provider.lastUserText()).contains("What is this?").contains(instruction);
    }

    /** Vision-capable mock that records the text of the last user message it was sent. */
    private static final class CapturingProvider implements AiProvider, LangchainBridge.ChatModelAccess {
        private final String reply;
        private final List<String> userTexts = new ArrayList<>();

        CapturingProvider(String reply) { this.reply = reply; }

        String lastUserText() { return userTexts.get(userTexts.size() - 1); }

        @Override public String       name()           { return "capturing"; }
        @Override public String       modelId()        { return "mock"; }
        @Override public ProviderType type()           { return ProviderType.CUSTOM; }
        @Override public boolean      supportsVision() { return true; }

        @Override
        public ChatModel toChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse doChat(ChatRequest request) {
                    List<ChatMessage> messages = request.messages();
                    UserMessage user = (UserMessage) messages.get(messages.size() - 1);
                    // contents(), not singleText(): a vision message also carries an image
                    userTexts.add(user.contents().stream()
                        .filter(c -> c instanceof dev.langchain4j.data.message.TextContent)
                        .map(c -> ((dev.langchain4j.data.message.TextContent) c).text())
                        .reduce("", String::concat));
                    return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
                }
            };
        }
    }
}
