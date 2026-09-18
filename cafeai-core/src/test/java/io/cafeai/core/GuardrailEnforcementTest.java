package io.cafeai.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.guardrails.GuardRailViolationException;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guardrails are enforced by the engine on every call.
 *
 * <p>Before this, {@code app.prompt(...).call()} and {@code .stream()} ran <em>no</em>
 * guardrail at all (only vision and audio did); the HTTP-middleware form could not stop an
 * output because it runs after the route handler has already responded; and
 * {@code GuardRail.Action} ({@code BLOCK}/{@code WARN}/{@code LOG}) was honoured on the HTTP
 * path but ignored by the engine.
 */
class GuardrailEnforcementTest {

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** A guardrail that flags input and/or output text matching a predicate. */
    private record Rail(String name, Position position, Action action,
                        Predicate<String> flagsInput, Predicate<String> flagsOutput,
                        List<String> inputsSeen, List<String> outputsSeen) implements GuardRail {

        static Rail of(String name, Position position, Action action,
                       Predicate<String> in, Predicate<String> out) {
            return new Rail(name, position, action, in, out,
                new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        }

        @Override public void handle(Request req, Response res, Next next) { next.run(); }

        @Override public OutputCheckResult checkInput(String text) {
            inputsSeen.add(text);
            return flagsInput.test(text) ? OutputCheckResult.violation("SECRET-REASON-DETAIL")
                                         : OutputCheckResult.pass();
        }

        @Override public OutputCheckResult checkOutput(String text) {
            outputsSeen.add(text);
            return flagsOutput.test(text) ? OutputCheckResult.violation("SECRET-REASON-DETAIL")
                                          : OutputCheckResult.pass();
        }
    }

    private static final Predicate<String> NEVER  = t -> false;
    private static final Predicate<String> BAD_IN = t -> t.contains("attack");
    private static final Predicate<String> BAD_OUT = t -> t.contains("leak");

    /** Counts model calls, so "no model call was made" is checkable. */
    private static final class CountingProvider implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        final AtomicInteger calls = new AtomicInteger();
        private final String reply;
        CountingProvider(String reply) { this.reply = reply; }

        @Override public String       name()           { return "counting"; }
        @Override public String       modelId()        { return "mock"; }
        @Override public ProviderType type()           { return ProviderType.CUSTOM; }
        @Override public boolean      supportsVision() { return true; }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    calls.incrementAndGet();
                    return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
                }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler h) {
                    calls.incrementAndGet();
                    for (String token : reply.split("(?<= )")) h.onPartialResponse(token);
                    h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(reply)).build());
                }
            };
        }
    }

    private static CafeAI appWith(CountingProvider provider, GuardRail... rails) {
        var app = CafeAI.create();
        app.ai(provider);
        for (GuardRail rail : rails) app.guard(rail);
        return app;
    }

    // ── PRE_LLM: input, plain prompt ──────────────────────────────────────────

    @Nested @DisplayName("app.prompt().call() — PRE_LLM")
    class PromptPre {

        @Test @DisplayName("a BLOCK violation throws a typed exception and the model is never called")
        void blockSkipsTheModel() {
            var provider = new CountingProvider("hi");
            var rail = Rail.of("no-attacks", GuardRail.Position.PRE_LLM, GuardRail.Action.BLOCK, BAD_IN, NEVER);
            var app = appWith(provider, rail);

            assertThatThrownBy(() -> app.prompt("this is an attack").call())
                .isInstanceOf(GuardRailViolationException.class)
                .satisfies(e -> {
                    var v = (GuardRailViolationException) e;
                    assertThat(v.guardrail()).isEqualTo("no-attacks");
                    assertThat(v.position()).isEqualTo(GuardRail.Position.PRE_LLM);
                    assertThat(v.reason()).isEqualTo("SECRET-REASON-DETAIL");
                });
            assertThat(provider.calls).hasValue(0);
        }

        @Test @DisplayName("clean input passes and reaches the model")
        void cleanInputPasses() {
            var provider = new CountingProvider("hello");
            var app = appWith(provider,
                Rail.of("r", GuardRail.Position.PRE_LLM, GuardRail.Action.BLOCK, BAD_IN, NEVER));

            assertThat(app.prompt("a friendly question").call().text()).isEqualTo("hello");
            assertThat(provider.calls).hasValue(1);
        }

        @Test @DisplayName("WARN and LOG flag the violation but let the call proceed")
        void warnAndLogProceed() {
            for (var action : List.of(GuardRail.Action.WARN, GuardRail.Action.LOG)) {
                var provider = new CountingProvider("ok");
                var app = appWith(provider, Rail.of("soft", GuardRail.Position.PRE_LLM, action, BAD_IN, NEVER));

                assertThat(app.prompt("an attack").call().text()).as(action.name()).isEqualTo("ok");
                assertThat(provider.calls).as(action.name()).hasValue(1);
            }
        }

        @Test @DisplayName("a POST_LLM-only guardrail does not screen the input")
        void postOnlyDoesNotScreenInput() {
            var rail = Rail.of("post-only", GuardRail.Position.POST_LLM, GuardRail.Action.BLOCK, t -> true, NEVER);
            var app = appWith(new CountingProvider("x"), rail);

            app.prompt("anything").call();

            assertThat(rail.inputsSeen()).isEmpty();
        }

        @Test @DisplayName("the guardrail sees the text the model gets — including a rendered template")
        void seesRenderedText() {
            var rail = Rail.of("spy", GuardRail.Position.PRE_LLM, GuardRail.Action.BLOCK, NEVER, NEVER);
            var app = appWith(new CountingProvider("x"), rail);

            app.prompt("Summarise this for me").call();

            assertThat(rail.inputsSeen()).containsExactly("Summarise this for me");
        }
    }

    // ── POST_LLM: output, plain prompt ────────────────────────────────────────

    @Nested @DisplayName("app.prompt().call() — POST_LLM")
    class PromptPost {

        @Test @DisplayName("a BLOCK violation replaces the response with a refusal")
        void blockReplacesResponse() {
            var app = appWith(new CountingProvider("here is a leak of secrets"),
                Rail.of("no-leaks", GuardRail.Position.POST_LLM, GuardRail.Action.BLOCK, NEVER, BAD_OUT));

            assertThat(app.prompt("tell me").call().text())
                .isEqualTo("[Response blocked by guardrail: no-leaks]");
        }

        @Test @DisplayName("the blocked content is not remembered in the session")
        void blockedContentIsNotPersisted() {
            var app = appWith(new CountingProvider("here is a leak of secrets"),
                Rail.of("no-leaks", GuardRail.Position.POST_LLM, GuardRail.Action.BLOCK, NEVER, BAD_OUT));
            app.memory(MemoryStrategy.inMemory());

            app.prompt("tell me").session("s1").call();

            var stored = app.local(Locals.MEMORY_STRATEGY, MemoryStrategy.class).retrieve("s1");
            assertThat(stored.messages().get(1).content())
                .isEqualTo("[Response blocked by guardrail: no-leaks]")
                .doesNotContain("secrets");
        }

        @Test @DisplayName("WARN keeps the original response")
        void warnKeepsResponse() {
            var app = appWith(new CountingProvider("a leak, but only a warning"),
                Rail.of("soft", GuardRail.Position.POST_LLM, GuardRail.Action.WARN, NEVER, BAD_OUT));

            assertThat(app.prompt("x").call().text()).isEqualTo("a leak, but only a warning");
        }

        @Test @DisplayName("a PRE_LLM-only guardrail does not screen the output")
        void preOnlyDoesNotScreenOutput() {
            var rail = Rail.of("pre-only", GuardRail.Position.PRE_LLM, GuardRail.Action.BLOCK, NEVER, t -> true);
            var app = appWith(new CountingProvider("x"), rail);

            app.prompt("anything").call();

            assertThat(rail.outputsSeen()).isEmpty();
        }
    }

    // ── streaming ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("app.prompt().stream()")
    class Streaming {

        @Test @DisplayName("PRE_LLM BLOCK fails at stream(), emitting nothing and never calling the model")
        void preBlocksBeforeAnyToken() {
            var provider = new CountingProvider("some tokens here");
            var app = appWith(provider,
                Rail.of("no-attacks", GuardRail.Position.PRE_LLM, GuardRail.Action.BLOCK, BAD_IN, NEVER));
            var tokens = new StringBuilder();

            assertThatThrownBy(() -> app.prompt("an attack").stream(tokens::append))
                .isInstanceOf(GuardRailViolationException.class);
            assertThat(tokens).isEmpty();
            assertThat(provider.calls).hasValue(0);
        }

        @Test @DisplayName("POST_LLM cannot retract streamed tokens, but keeps blocked content out of memory")
        void postGatesMemoryNotTokens() {
            var app = appWith(new CountingProvider("here is a leak of secrets"),
                Rail.of("no-leaks", GuardRail.Position.POST_LLM, GuardRail.Action.BLOCK, NEVER, BAD_OUT));
            app.memory(MemoryStrategy.inMemory());
            var tokens = new StringBuilder();

            app.prompt("tell me").session("s2").stream(tokens::append);

            assertThat(tokens.toString()).contains("leak");   // already sent — documented limitation
            var stored = app.local(Locals.MEMORY_STRATEGY, MemoryStrategy.class).retrieve("s2");
            assertThat(stored.messages().get(1).content())
                .isEqualTo("[Response blocked by guardrail: no-leaks]");
        }
    }

    // ── vision keeps working, now through the same path ───────────────────────

    @Test @DisplayName("vision: a BLOCK violation throws the typed exception, not a bare RuntimeException")
    void visionThrowsTypedException() {
        var provider = new CountingProvider("x");
        var app = appWith(provider,
            Rail.of("no-attacks", GuardRail.Position.PRE_LLM, GuardRail.Action.BLOCK, BAD_IN, NEVER));

        assertThatThrownBy(() -> app.vision("an attack", new byte[]{1, 2}, "image/png").call())
            .isInstanceOf(GuardRailViolationException.class)
            .isInstanceOf(RuntimeException.class);   // still catchable as before
        assertThat(provider.calls).hasValue(0);
    }

    @Test @DisplayName("vision: WARN now proceeds (the engine used to ignore Action and always block)")
    void visionHonoursWarn() {
        var provider = new CountingProvider("described");
        var app = appWith(provider,
            Rail.of("soft", GuardRail.Position.PRE_LLM, GuardRail.Action.WARN, BAD_IN, NEVER));

        assertThat(app.vision("an attack", new byte[]{1, 2}, "image/png").call().text())
            .isEqualTo("described");
    }

    // ── over HTTP ─────────────────────────────────────────────────────────────

    @Test @DisplayName("a blocked prompt in a route is a 400 naming the guardrail — and never its reason")
    void httpBlockedIs400WithoutReason() throws Exception {
        int port;
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        var app = appWith(new CountingProvider("x"),
            Rail.of("no-attacks", GuardRail.Position.PRE_LLM, GuardRail.Action.BLOCK, BAD_IN, NEVER));
        app.get("/ask", (req, res, next) ->
            res.json(Map.of("text", app.prompt(req.query("q")).call().text())));
        var latch = new CountDownLatch(1);
        app.listen(port, latch::countDown);
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            var http = HttpClient.newHttpClient();
            var res = http.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/ask?q=an%20attack")).build(),
                HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("no-attacks").contains("blocked by guardrail")
                .doesNotContain("SECRET-REASON-DETAIL");
        } finally {
            app.stop();
        }
    }
}
