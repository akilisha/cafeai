package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code cafeai-guardrails} on the classpath, the {@code GuardRail} factories in core resolve
 * to the real implementations. (Without it they throw; that is tested in {@code cafeai-core}.)
 */
@DisplayName("GuardRail factories resolve to the real implementations")
class GuardRailFactoryTest {

    @Test
    @DisplayName("pii() is a both-sided guardrail named pii")
    void pii() {
        var rail = GuardRail.pii();
        assertThat(rail.name()).isEqualTo("pii");
        assertThat(rail.position()).isEqualTo(GuardRail.Position.BOTH);
    }

    @Test
    @DisplayName("jailbreak() and promptInjection() screen input only")
    void inputOnly() {
        assertThat(GuardRail.jailbreak().position()).isEqualTo(GuardRail.Position.PRE_LLM);
        assertThat(GuardRail.promptInjection().position()).isEqualTo(GuardRail.Position.PRE_LLM);
    }

    @Test
    @DisplayName("secrets() is a both-sided guardrail")
    void secrets() {
        assertThat(GuardRail.secrets()).isInstanceOf(SecretsGuardRail.class);
        assertThat(GuardRail.secrets().position()).isEqualTo(GuardRail.Position.BOTH);
    }

    @Test
    @DisplayName("toxicity() is a both-sided guardrail")
    void toxicity() {
        assertThat(GuardRail.toxicity().position()).isEqualTo(GuardRail.Position.BOTH);
    }

    @Test
    @DisplayName("regulatory().gdpr().hipaa() builds a composite guardrail")
    void regulatory() {
        var rail = GuardRail.regulatory().gdpr().hipaa();
        assertThat(rail).isInstanceOf(RegulatoryGuardRailImpl.class);
        assertThat(rail.name()).contains("gdpr").contains("hipaa");
        assertThat(rail.position()).isEqualTo(GuardRail.Position.PRE_LLM);
    }

    @Test
    @DisplayName("topicBoundary().allow().deny() builds a topic-boundary guardrail")
    void topicBoundary() {
        var rail = GuardRail.topicBoundary()
                .allow("customer service", "orders")
                .deny("politics", "medical advice");
        assertThat(rail).isInstanceOf(TopicBoundaryGuardRailImpl.class);
        assertThat(rail.name()).isEqualTo("topic-boundary");
    }
}
