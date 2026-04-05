package io.cafeai.agents;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Phase 2 scaffold tests — verify the module compiles and basic structure is correct.
 * Functional tests begin in Phase 3.
 */
class AgentScaffoldTest {

    interface EchoAgent {
        String echo(String message);
    }

    @Test
    void agentConfig_creates_with_interface() {
        var config = new AgentConfig<>(EchoAgent.class);
        assertThat(config.agentInterface()).isEqualTo(EchoAgent.class);
    }

    @Test
    void agentConfig_fluent_system_prompt() {
        var config = new AgentConfig<>(EchoAgent.class)
            .system("You are a helpful assistant.");
        assertThat(config.systemPrompt()).isEqualTo("You are a helpful assistant.");
    }

    @Test
    void agentConfig_fluent_tool_registration() {
        var config = new AgentConfig<>(EchoAgent.class)
            .tool(new Object());
        assertThat(config.tools()).hasSize(1);
    }

    @Test
    void agentRegistry_registers_and_finds() {
        var registry = new AgentRegistry();
        var config   = new AgentConfig<>(EchoAgent.class);
        registry.register("echo", config);
        assertThat(registry.isRegistered("echo")).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void agentRegistry_unknown_agent_not_registered() {
        var registry = new AgentRegistry();
        assertThat(registry.isRegistered("unknown")).isFalse();
    }
}
