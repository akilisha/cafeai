package io.cafeai.core.identity;

import io.cafeai.core.memory.ConversationKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IdentityMode: once an app serves verified callers, unscoped conversation work is refused")
class IdentityModeTest {

    @AfterEach
    void reset() {
        IdentityMode.resetForTests();
    }

    @Test @DisplayName("off: a conversation id with no request in scope is used as given")
    void off() {
        assertThat(IdentityMode.enabled()).isFalse();
        assertThat(ConversationKeys.forCurrentCaller("s-1")).isEqualTo("s-1");
    }

    @Test @DisplayName("on: a conversation id with no request in scope is refused; no id is still fine")
    void on() {
        IdentityMode.enable();
        assertThatThrownBy(() -> ConversationKeys.forCurrentCaller("s-1"))
                .isInstanceOf(IdentityRequiredException.class)
                .hasMessageContaining("RequestScope");
        assertThat(ConversationKeys.forCurrentCaller(null)).isNull();
    }
}
