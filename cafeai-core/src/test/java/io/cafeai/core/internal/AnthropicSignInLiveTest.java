package io.cafeai.core.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Anthropic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Claude Console sign-in ({@code cafeai login claude}, through Anthropic's {@code ant}) against
 * the real Claude API (MILESTONE-20 phase 5). Runs under {@code ./gradlew :cafeai-core:liveTest}
 * with {@code ANTHROPIC_SIGNIN_LIVE_MODEL} set and no {@code ANTHROPIC_API_KEY}; skipped otherwise.
 *
 * <p>{@code ANTHROPIC_SIGNIN_RENEW=1} also renews the sign-in at Anthropic: it marks the stored
 * token as expiring in a minute (only ever earlier than it really does), so CafeAI renews it and
 * writes the new one back for {@code ant} too.
 */
@Tag("live")
@DisplayName("Claude Console sign-in, live")
class AnthropicSignInLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static AnthropicProfile profile() {
        String model = System.getenv("ANTHROPIC_SIGNIN_LIVE_MODEL");
        Assumptions.assumeTrue(model != null && !model.isBlank(),
                "set ANTHROPIC_SIGNIN_LIVE_MODEL after `cafeai login claude` to run against Anthropic");
        Assumptions.assumeTrue(System.getenv("ANTHROPIC_API_KEY") == null,
                "unset ANTHROPIC_API_KEY: it would be used instead of the sign-in");
        var profile = AnthropicProfile.active(System::getenv).orElseThrow(() ->
                new AssertionError("no Anthropic profile: run `cafeai login claude` first"));
        assertThat(profile.isSignIn()).as("the profile is an `ant auth login` sign-in").isTrue();
        return profile;
    }

    @Test @DisplayName("a real call with the sign-in's token, no API key")
    void call() {
        var profile = profile();
        var stored = profile.stored().orElse(null);
        Assumptions.assumeTrue(stored != null, "signed out: `cafeai login claude` first");
        System.out.println("[live] profile " + profile.name() + ": organisation "
                + stored.path("organization_name").asText("?") + ", workspace "
                + stored.path("workspace_name").asText("?") + ", token expires in "
                + (stored.path("expires_at").asLong() - Instant.now().getEpochSecond()) + "s");

        var app = CafeAI.create();
        app.ai(Anthropic.of(System.getenv("ANTHROPIC_SIGNIN_LIVE_MODEL")));
        String answer = app.prompt("Reply with the single word: ready").call().text();
        System.out.println("[live] answer: " + answer);
        assertThat(answer).isNotBlank();
    }

    @Test @DisplayName("after `cafeai logout claude`, a call says to sign in again")
    void signedOut() {
        var profile = profile();
        Assumptions.assumeTrue(profile.stored().isEmpty(), "signed in: this checks the signed-out message");

        var app = CafeAI.create();
        app.ai(Anthropic.of(System.getenv("ANTHROPIC_SIGNIN_LIVE_MODEL")));
        assertThatThrownBy(() -> app.prompt("Reply with the single word: ready").call())
                .satisfies(e -> System.out.println("[live] signed out: " + e))
                .hasMessageContaining("cafeai login claude");
    }

    @Test @DisplayName("a real renewal at Anthropic, written back for ant")
    void renew() throws Exception {
        var profile = profile();
        Assumptions.assumeTrue("1".equals(System.getenv("ANTHROPIC_SIGNIN_RENEW")),
                "set ANTHROPIC_SIGNIN_RENEW=1 to renew the sign-in at Anthropic");
        Path file = profile.credentialsFile();
        ObjectNode before = (ObjectNode) JSON.readTree(file.toFile());
        String oldToken = before.path("access_token").asText();
        before.put("expires_at", Instant.now().getEpochSecond() + 60);
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(before));

        String token = profile.credentials().token();

        ObjectNode after = (ObjectNode) JSON.readTree(file.toFile());
        long lifetime = after.path("expires_at").asLong() - Instant.now().getEpochSecond();
        System.out.println("[live] renewed: new token " + !token.equals(oldToken) + ", new refresh token "
                + !after.path("refresh_token").asText().equals(before.path("refresh_token").asText())
                + ", lifetime " + lifetime + "s, fields " + fieldNames(after));
        assertThat(token).isNotEqualTo(oldToken);
        assertThat(after.path("access_token").asText()).isEqualTo(token);
        assertThat(lifetime).isGreaterThan(AnthropicProfile.RENEW_BEFORE.toSeconds());
    }

    private static String fieldNames(ObjectNode node) {
        var names = new java.util.ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }
}
