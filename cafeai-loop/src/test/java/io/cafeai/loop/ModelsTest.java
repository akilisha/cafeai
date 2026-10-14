package io.cafeai.loop;

import io.cafeai.core.ai.AiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ROADMAP-03, 2.2 and 2.3: a command's model, named provider:model, reached through CafeAI's credential lookup. */
@DisplayName("The models: provider:model, the command's own or the default, through CafeAI's lookup")
class ModelsTest {

    @TempDir Path project;

    private Settings settings(String yaml) throws IOException {
        Files.writeString(project.resolve(Settings.FILE), yaml);
        return Settings.read(project);
    }

    @Test @DisplayName("each provider's id becomes CafeAI's provider, with the model id after the colon")
    void providers() {
        assertThat(Models.provider("anthropic:claude-opus-5-5").type()).isEqualTo(AiProvider.ProviderType.ANTHROPIC);
        assertThat(Models.provider("anthropic:claude-opus-5-5").modelId()).isEqualTo("claude-opus-5-5");
        assertThat(Models.provider("openai:gpt-5").type()).isEqualTo(AiProvider.ProviderType.OPENAI);
        assertThat(Models.provider("ollama:llama3.3").type()).isEqualTo(AiProvider.ProviderType.OLLAMA);
        for (String id : new String[] {"gemini:g-1", "grok:x-1", "mistral:m-1", "nova:n-1"}) {
            assertThat(Models.provider(id).modelId()).as(id).isEqualTo(id.substring(id.indexOf(':') + 1));
        }
        assertThat(Models.provider("ollama:qwen3:8b").modelId()).as("only the first colon splits").isEqualTo("qwen3:8b");
    }

    @Test @DisplayName("2.3: no credentials set by the loop: CafeAI's lookup (code, the vendor's variable, cafeai login) decides")
    void credentialLookup() {
        assertThat(Models.provider("anthropic:claude-opus-5-5").credentials()).isNull();
        assertThat(Models.provider("openai:gpt-5").credentials()).isNull();
    }

    @Test @DisplayName("a command's own model, else the default")
    void forCommand() throws IOException {
        Settings s = settings("models: {default: \"anthropic:a-1\", init: \"openai:o-1\"}");
        assertThat(Models.forCommand(s, "init").modelId()).isEqualTo("o-1");
        assertThat(Models.forCommand(s, "plan").modelId()).isEqualTo("a-1");
    }

    @Test @DisplayName("no model named anywhere: the command says how to name one, and names none itself")
    void none() {
        assertThatThrownBy(() -> Models.forCommand(Settings.defaults(), "init"))
                .hasMessageContaining("models.default").hasMessageContaining(Settings.FILE)
                .hasMessageContaining("provider:model");
    }

    @Test @DisplayName("a malformed id is refused")
    void malformed() {
        assertThatThrownBy(() -> Models.provider("claude-opus-5-5")).hasMessageContaining("provider:model");
        assertThatThrownBy(() -> Models.provider("acme:x")).hasMessageContaining("anthropic, openai");
        assertThatThrownBy(() -> Models.provider("anthropic:")).hasMessageContaining("provider:model");
    }

    @Test @DisplayName("an Anthropic model has room to write a whole artifact in one go; the others keep the model's own limit")
    void outputRoom() {
        assertThat(Models.provider("anthropic:a-1").maxTokens()).isEqualTo(Models.ANTHROPIC_MAX_OUTPUT);
        assertThat(Models.ANTHROPIC_MAX_OUTPUT).isGreaterThanOrEqualTo(16_000);
        assertThat(Models.provider("openai:o-1").maxTokens()).isNull();
        assertThat(Models.provider("ollama:qwen3:8b").maxTokens()).isNull();
    }

    @Test @DisplayName("every command model has time to write a whole artifact, not a chat call's minute")
    void timeToWrite() {
        for (String id : List.of("anthropic:a-1", "openai:o-1", "gemini:g-1", "ollama:qwen3:8b")) {
            assertThat(Models.provider(id).timeout()).as(id).isEqualTo(Models.TIMEOUT);
        }
        assertThat(Models.TIMEOUT).isGreaterThanOrEqualTo(java.time.Duration.ofMinutes(5));
    }
}
