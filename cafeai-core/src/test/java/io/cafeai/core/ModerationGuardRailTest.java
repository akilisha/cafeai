package io.cafeai.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.moderation.Moderation;
import dev.langchain4j.model.moderation.ModerationModel;
import dev.langchain4j.model.moderation.ModerationRequest;
import dev.langchain4j.model.moderation.ModerationResponse;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.guardrails.GuardRailViolationException;
import io.cafeai.core.guardrails.ModerationGuardRail;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ModerationGuardRail}: a LangChain4j {@link ModerationModel} used as a CafeAI guardrail.
 * The model is faked (an interface implementation, no network), and everything is exercised
 * through real {@code app.prompt()} calls, so it also proves the engine enforces it.
 */
class ModerationGuardRailTest {

    /** A moderation model that flags text matching a predicate, or fails on demand. */
    private static final class FakeModeration implements ModerationModel {
        final List<String> seen = new CopyOnWriteArrayList<>();
        private final Predicate<String> flags;
        RuntimeException failure;

        FakeModeration(Predicate<String> flags) { this.flags = flags; }

        @Override public ModerationResponse doModerate(ModerationRequest request) {
            seen.addAll(request.texts());
            if (failure != null) throw failure;
            boolean flagged = request.texts().stream().anyMatch(flags);
            return ModerationResponse.builder()
                .moderation(flagged ? Moderation.flagged(request.texts().get(0)) : Moderation.notFlagged())
                .build();
        }
    }

    private static final class CountingProvider implements AiProvider, LangchainBridge.ChatModelAccess {
        final AtomicInteger calls = new AtomicInteger();
        private final String reply;
        CountingProvider(String reply) { this.reply = reply; }
        @Override public String       name()    { return "counting"; }
        @Override public String       modelId() { return "mock"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }
        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) {
                    calls.incrementAndGet();
                    return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
                }
            };
        }
    }

    private static final Predicate<String> HARMFUL = t -> t.contains("harmful");

    private static CafeAI app(CountingProvider provider, GuardRail rail) {
        var app = CafeAI.create();
        app.ai(provider);
        app.guard(rail);
        return app;
    }

    // ── the verdict is enforced ───────────────────────────────────────────────

    @Test @DisplayName("flagged input is blocked, and the model is never called")
    void flaggedInputBlocks() {
        var moderation = new FakeModeration(HARMFUL);
        var provider = new CountingProvider("hi");
        var app = app(provider, GuardRail.moderation(moderation));

        assertThatThrownBy(() -> app.prompt("something harmful").call())
            .isInstanceOf(GuardRailViolationException.class)
            .satisfies(e -> assertThat(((GuardRailViolationException) e).guardrail()).isEqualTo("moderation"));
        assertThat(provider.calls).hasValue(0);
        assertThat(moderation.seen).containsExactly("something harmful");
    }

    @Test @DisplayName("clean input passes; both input and output are sent to the moderation model")
    void cleanInputPasses() {
        var moderation = new FakeModeration(HARMFUL);
        var app = app(new CountingProvider("a kind reply"), GuardRail.moderation(moderation));

        assertThat(app.prompt("a kind question").call().text()).isEqualTo("a kind reply");
        assertThat(moderation.seen).containsExactly("a kind question", "a kind reply");
    }

    @Test @DisplayName("flagged output is replaced with a refusal")
    void flaggedOutputIsReplaced() {
        var app = app(new CountingProvider("this is harmful"),
            GuardRail.moderation(new FakeModeration(HARMFUL)));

        assertThat(app.prompt("a kind question").call().text())
            .isEqualTo("[Response blocked by guardrail: moderation]");
    }

    // ── configuration ─────────────────────────────────────────────────────────

    @Test @DisplayName("defaults: named 'moderation', BOTH positions, BLOCK")
    void defaults() {
        var rail = GuardRail.moderation(new FakeModeration(HARMFUL));

        assertThat(rail.name()).isEqualTo("moderation");
        assertThat(rail.position()).isEqualTo(GuardRail.Position.BOTH);
        assertThat(rail.action()).isEqualTo(GuardRail.Action.BLOCK);
    }

    @Test @DisplayName("at(PRE_LLM) moderates only what users send")
    void inputOnly() {
        var moderation = new FakeModeration(HARMFUL);
        var app = app(new CountingProvider("this is harmful"),
            GuardRail.moderation(moderation).at(GuardRail.Position.PRE_LLM));

        assertThat(app.prompt("a kind question").call().text()).isEqualTo("this is harmful");
        assertThat(moderation.seen).containsExactly("a kind question");
    }

    @Test @DisplayName("at(POST_LLM) moderates only what the model returns")
    void outputOnly() {
        var moderation = new FakeModeration(t -> false);
        var app = app(new CountingProvider("reply"),
            GuardRail.moderation(moderation).at(GuardRail.Position.POST_LLM));

        app.prompt("a question").call();

        assertThat(moderation.seen).containsExactly("reply");
    }

    @Test @DisplayName("WARN records the flag and lets the call proceed")
    void warnProceeds() {
        var provider = new CountingProvider("answer");
        var app = app(provider, GuardRail.moderation(new FakeModeration(HARMFUL))
            .action(GuardRail.Action.WARN));

        assertThat(app.prompt("something harmful").call().text()).isEqualTo("answer");
        assertThat(provider.calls).hasValue(1);
    }

    @Test @DisplayName("named(...) renames it in reports; the configured rail is a new immutable value")
    void namedAndImmutable() {
        var base = GuardRail.moderation(new FakeModeration(HARMFUL));
        var renamed = base.named("brand-safety");

        assertThat(renamed.name()).isEqualTo("brand-safety");
        assertThat(base.name()).isEqualTo("moderation");
    }

    @Test @DisplayName("blank text is never sent to the moderation model")
    void blankIsSkipped() {
        var moderation = new FakeModeration(HARMFUL);
        var rail = GuardRail.moderation(moderation);

        assertThat(rail.checkInput("   ").isViolation()).isFalse();
        assertThat(rail.checkOutput(null).isViolation()).isFalse();
        assertThat(moderation.seen).isEmpty();
    }

    // ── failure policy ────────────────────────────────────────────────────────

    @Test @DisplayName("fails CLOSED by default: a moderation outage blocks rather than waving text through")
    void failsClosed() {
        var moderation = new FakeModeration(HARMFUL);
        moderation.failure = new IllegalStateException("moderation API down");
        var provider = new CountingProvider("hi");
        var app = app(provider, GuardRail.moderation(moderation));

        assertThatThrownBy(() -> app.prompt("a kind question").call())
            .isInstanceOf(GuardRailViolationException.class)
            .satisfies(e -> assertThat(((GuardRailViolationException) e).reason())
                .contains("unavailable").contains("failing closed"));
        assertThat(provider.calls).hasValue(0);
    }

    @Test @DisplayName("failOpen() lets text through when the moderation call fails")
    void failOpen() {
        var moderation = new FakeModeration(HARMFUL);
        moderation.failure = new IllegalStateException("moderation API down");
        var app = app(new CountingProvider("hi"), GuardRail.moderation(moderation).failOpen());

        assertThat(app.prompt("a kind question").call().text()).isEqualTo("hi");
    }

    // ── the OpenAI convenience returns LangChain4j's own type ─────────────────

    @Test @DisplayName("OpenAI.moderation(id) returns a plain LangChain4j ModerationModel — no CafeAI wrapper")
    void openAiFactoryReturnsLangChain4jType() {
        ModerationModel model = OpenAI.moderation("omni-moderation-latest");

        assertThat(model).isInstanceOf(dev.langchain4j.model.openai.OpenAiModerationModel.class);
        assertThat(GuardRail.moderation(model)).isInstanceOf(ModerationGuardRail.class);
    }
}
