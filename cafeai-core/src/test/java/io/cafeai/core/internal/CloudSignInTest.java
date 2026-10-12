package io.cafeai.core.internal;

import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.Credentials;
import io.cafeai.core.ai.OpenAI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** LOGIN.md §9: a provider pointed at a cloud uses that cloud's CLI sign-in by itself. */
@DisplayName("A provider pointed at a cloud uses the developer's cloud sign-in by itself")
class CloudSignInTest {

    private final Map<String, String> env = new HashMap<>();
    private final List<String> made = new ArrayList<>();

    /** A stand-in for each sign-in, named after it. */
    private record SignIn(String kind) implements Credentials {
        @Override public String token() { return kind; }
    }

    @BeforeEach
    void isolate() {
        LangchainBridge.environment = env::get;
        CloudSignIn.reset();
        CloudSignIn.factory = kind -> { made.add(kind); return new SignIn(kind); };
    }

    @AfterEach
    void restore() {
        LangchainBridge.environment = System::getenv;
        CloudSignIn.factory = CloudSignInTest::real;
        CloudSignIn.reset();
    }

    private static Credentials real(String kind) {
        throw new AssertionError("restored after the test: " + kind);
    }

    @ParameterizedTest(name = "Anthropic at {0}: {1}")
    @CsvSource({
            "https://acme.services.ai.azure.com/anthropic, azure-foundry",
            "https://ACME.Services.AI.Azure.com/anthropic/v1, azure-foundry",
            "https://bedrock-mantle.us-east-1.api.aws/anthropic, aws:us-east-1",
            "https://bedrock-mantle.eu-west-3.api.aws/anthropic, aws:eu-west-3",
            "http://acme.services.ai.azure.com/anthropic, none",
            "https://acme.services.ai.azure.com.example.com/anthropic, none",
            "https://evil-bedrock-mantle.us-east-1.api.aws/anthropic, none",
            "https://api.deepseek.com/anthropic, none",
            "https://acme.openai.azure.com/anthropic, none",
            "not a url, none"
    })
    void anthropicHosts(String baseUrl, String expected) {
        assertThat(CloudSignIn.anthropicKind(baseUrl).map(CloudSignIn.Kind::key).orElse("none")).isEqualTo(expected);
    }

    @ParameterizedTest(name = "OpenAI at {0}: {1}")
    @CsvSource({
            "https://acme.openai.azure.com/openai/v1, azure-openai",
            "https://acme.cognitiveservices.azure.com/openai/v1, azure-openai",
            "https://api.openai.com/v1, none",
            "https://bedrock-mantle.us-east-1.api.aws/v1, none",
            "http://acme.openai.azure.com/openai/v1, none"
    })
    void openAiHosts(String baseUrl, String expected) {
        assertThat(CloudSignIn.openAiKind(baseUrl).map(CloudSignIn.Kind::key).orElse("none")).isEqualTo(expected);
    }

    @Test @DisplayName("Claude on Vertex AI with no credentials: the Google sign-in")
    void vertex() {
        assertThat(LangchainBridge.anthropicCredentials(Anthropic.onVertex("claude-opus-5-5", "p", "global")))
                .isEqualTo(new SignIn("google"));
    }

    @Test @DisplayName("Claude in Foundry or Bedrock with no credentials and no ANTHROPIC_API_KEY: that cloud's sign-in")
    void anthropicClouds() {
        assertThat(LangchainBridge.anthropicCredentials(Anthropic.of("claude-opus-5-5")
                .withBaseUrl("https://acme.services.ai.azure.com/anthropic"))).isEqualTo(new SignIn("azure-foundry"));
        assertThat(LangchainBridge.anthropicCredentials(Anthropic.of("anthropic.claude-opus-5-5")
                .withBaseUrl("https://bedrock-mantle.us-east-1.api.aws/anthropic"))).isEqualTo(new SignIn("aws:us-east-1"));
    }

    @Test @DisplayName("withCredentials wins, then ANTHROPIC_API_KEY; another host gets no sign-in")
    void anthropicOrder() {
        var mine = Credentials.staticKey("mine");
        assertThat(LangchainBridge.anthropicCredentials(Anthropic.onVertex("m", "p", "global").withCredentials(mine))).isSameAs(mine);
        assertThat(LangchainBridge.anthropicCredentials(Anthropic.of("m")
                .withBaseUrl("https://acme.services.ai.azure.com/anthropic").withCredentials(mine))).isSameAs(mine);

        env.put("ANTHROPIC_API_KEY", "sk-ant-key");
        assertThat(LangchainBridge.anthropicCredentials(Anthropic.of("m")
                .withBaseUrl("https://acme.services.ai.azure.com/anthropic"))).as("the key is sent instead").isNull();
        env.remove("ANTHROPIC_API_KEY");

        assertThat(LangchainBridge.anthropicCredentials(Anthropic.of("m").withBaseUrl("https://api.deepseek.com/anthropic"))).isNull();
        assertThat(made).doesNotContain("google", "azure-openai");
    }

    @Test @DisplayName("Azure OpenAI with no credentials and no OPENAI_API_KEY: the Azure sign-in; never the saved OpenAI key")
    void azureOpenAi() {
        var provider = OpenAI.of("gpt-deployment").withBaseUrl("https://acme.openai.azure.com/openai/v1");
        assertThat(LangchainBridge.openAiCredentials(provider)).isEqualTo(new SignIn("azure-openai"));

        env.put("OPENAI_API_KEY", "sk-key");
        assertThat(LangchainBridge.openAiCredentials(provider)).as("the variable's key is sent instead").isNull();
        env.remove("OPENAI_API_KEY");

        assertThat(LangchainBridge.openAiCredentials(OpenAI.of("gpt-4o-mini"))).as("OpenAI itself: the key").isNull();
    }

    @Test @DisplayName("one sign-in per cloud, shared by every provider")
    void shared() {
        var a = LangchainBridge.anthropicCredentials(Anthropic.onVertex("a", "p", "global"));
        var b = LangchainBridge.anthropicCredentials(Anthropic.onVertex("b", "q", "us-east5"));
        assertThat(a).isSameAs(b);
        assertThat(made).containsExactly("google");
    }
}
