package io.cafeai.core.memory;

import io.cafeai.core.identity.Identity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConversationKeys: a conversation belongs to the caller who started it")
class ConversationKeysTest {

    private static Identity caller(String issuer, String subject) {
        return Identity.builder(issuer, subject).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    @Test @DisplayName("without an identity the id is used as given, and no id means no conversation")
    void anonymous() {
        assertThat(ConversationKeys.forCaller(null, "s-1")).isEqualTo("s-1");
        assertThat(ConversationKeys.forCaller(null, null)).isNull();
        assertThat(ConversationKeys.forCurrentCaller("s-1")).isEqualTo("s-1");   // no request here
    }

    @Test @DisplayName("the same id is a different conversation for each caller, and the same one for the same caller")
    void scopedPerCaller() {
        var alice = caller("https://issuer.example.com", "alice");
        var bob = caller("https://issuer.example.com", "bob");
        String aliceKey = ConversationKeys.forCaller(alice, "s-1");

        assertThat(aliceKey).startsWith(ConversationKeys.SCOPED_PREFIX).endsWith(":s-1");
        assertThat(ConversationKeys.forCaller(bob, "s-1")).isNotEqualTo(aliceKey);
        assertThat(ConversationKeys.forCaller(caller("https://other.example.com", "alice"), "s-1"))
                .isNotEqualTo(aliceKey);
        assertThat(ConversationKeys.forCaller(caller("https://issuer.example.com", "alice"), "s-1"))
                .isEqualTo(aliceKey);
    }

    @Test @DisplayName("issuer and subject can't be shuffled into another caller's scope")
    void noAmbiguity() {
        assertThat(ConversationKeys.forCaller(caller("https://a.example.com:x", "y"), "s"))
                .isNotEqualTo(ConversationKeys.forCaller(caller("https://a.example.com", "x:y"), "s"));
    }

    @Test @DisplayName("no personal data in the key")
    void noPersonalData() {
        assertThat(ConversationKeys.forCaller(caller("https://issuer.example.com", "alice@example.com"), "s-1"))
                .doesNotContain("alice").doesNotContain("issuer.example.com");
    }

    @Test @DisplayName("an anonymous caller can't name a scoped conversation")
    void reservedPrefix() {
        String aliceKey = ConversationKeys.forCaller(caller("https://issuer.example.com", "alice"), "s-1");
        assertThatThrownBy(() -> ConversationKeys.forCaller(null, aliceKey))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reserved");
    }
}
