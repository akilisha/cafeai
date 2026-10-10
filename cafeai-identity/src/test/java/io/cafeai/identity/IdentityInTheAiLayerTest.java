package io.cafeai.identity;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Pricing;
import io.cafeai.core.ai.UsageReport;
import io.cafeai.core.audit.AuditEvent;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.guardrails.GuardRailViolationException;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import io.cafeai.identity.dev.FakeIssuer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Identity in the AI layer: usage per caller, audit records, guardrail flags")
class IdentityInTheAiLayerTest {

    private static final String AUDIENCE = "orders-api";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Answers "ok, then a secret" with 100 tokens in and 20 out; streams from its own thread. */
    record Fixed(String modelId) implements AiProvider,
            LangchainBridge.ChatModelAccess, LangchainBridge.StreamingChatModelAccess {
        @Override public String       name() { return "fixed"; }
        @Override public ProviderType type() { return ProviderType.CUSTOM; }

        static ChatResponse answer() {
            return ChatResponse.builder().aiMessage(AiMessage.from("Fine so far. Then a secret. "))
                    .tokenUsage(new TokenUsage(100, 20, 120)).build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) { return answer(); }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    // Like a real provider: the answer arrives on another thread.
                    Thread.ofPlatform().start(() -> {
                        h.onPartialResponse("Fine so far. ");
                        h.onPartialResponse("Then a secret. ");
                        h.onCompleteResponse(answer());
                    });
                }
            };
        }
    }

    /** Flags input containing "forbidden" and output containing "secret". */
    record Rail(String name, Position position, Action action) implements GuardRail {
        @Override public void handle(Request req, Response res, Next next) { next.run(); }
        @Override public OutputCheckResult checkInput(String text) {
            return text.contains("forbidden") ? OutputCheckResult.violation("x") : OutputCheckResult.pass();
        }
        @Override public OutputCheckResult checkOutput(String text) {
            return text.contains("secret") ? OutputCheckResult.violation("x") : OutputCheckResult.pass();
        }
    }

    private static FakeIssuer fake;
    private CafeAI app;
    private final List<AuditEvent> audit = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startIssuer() {
        fake = FakeIssuer.start();
    }

    @AfterAll
    static void stopIssuer() {
        fake.close();
    }

    @AfterEach
    void stopApp() {
        if (app != null) app.stop();
    }

    private void start(GuardRail... rails) throws Exception {
        app = CafeAI.create();
        app.ai(new Fixed("fixed-1"));
        app.pricing(Pricing.of("fixed-1", 1.0, 2.0));
        for (GuardRail rail : rails) app.guard(rail);
        app.audit(audit::add);
        app.filter(Auth.bearer(fake.issuer(), AUDIENCE).optional());
        app.get("/ask", (req, res, next) -> {
            try {
                res.send(app.prompt(req.query("q") != null ? req.query("q") : "hello").call().text());
            } catch (GuardRailViolationException e) {
                res.status(400).send("blocked");
            }
        });
        app.get("/sse", (req, res, next) -> res.stream(app.prompt("tell me")));
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private HttpResponse<String> get(String path, String subject) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path));
        if (subject != null) {
            request.header("Authorization", "Bearer " + fake.token().subject(subject).audience(AUDIENCE).sign());
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Identity.Key key(String subject) {
        return new Identity.Key(fake.id(), subject);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.sleep(20);
        }
    }

    @Test @DisplayName("usage is credited to each caller; anonymous calls only to the route")
    void usagePerCaller() throws Exception {
        start();
        get("/ask", "alice");
        get("/ask", "alice");
        get("/ask", "bob");
        get("/ask", null);

        await(() -> app.usage().total().calls() == 4);
        UsageReport usage = app.usage();
        assertThat(usage.route("GET /ask").orElseThrow().calls()).isEqualTo(4);
        assertThat(usage.callers()).hasSize(2);
        UsageReport.CallerUsage alice = usage.caller(key("alice")).orElseThrow();
        assertThat(alice.calls()).isEqualTo(2);
        assertThat(alice.inputTokens()).isEqualTo(200);
        assertThat(alice.outputTokens()).isEqualTo(40);
        assertThat(alice.costKnown()).isTrue();
        assertThat(alice.cost()).isCloseTo(2 * (100 * 1.0 + 20 * 2.0) / 1_000_000, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(usage.caller(key("bob")).orElseThrow().calls()).isEqualTo(1);
    }

    @Test @DisplayName("each model call is an audit record naming the caller, route, model, tokens and cost")
    void modelCallAudit() throws Exception {
        start();
        get("/ask", "alice");
        get("/ask", null);

        await(() -> audit.size() == 2);
        var calls = audit.stream().map(AuditEvent.ModelCall.class::cast).toList();
        var alice = calls.stream().filter(c -> c.caller() != null).findFirst().orElseThrow();
        assertThat(alice.caller()).isEqualTo(key("alice"));
        assertThat(alice.route()).isEqualTo("GET /ask");
        assertThat(alice.model()).isEqualTo("fixed-1");
        assertThat(alice.inputTokens()).isEqualTo(100);
        assertThat(alice.outputTokens()).isEqualTo(20);
        assertThat(alice.cost()).isNotNull();
        assertThat(calls.stream().filter(c -> c.caller() == null)).hasSize(1);
    }

    @Test @DisplayName("a blocked request is a guardrail flag naming the caller, and no model call")
    void blockedRequestAudit() throws Exception {
        start(new Rail("no-forbidden", GuardRail.Position.PRE_LLM, GuardRail.Action.BLOCK));
        assertThat(get("/ask?q=forbidden", "carol").body()).isEqualTo("blocked");

        await(() -> !audit.isEmpty());
        assertThat(audit).hasSize(1);
        var flag = (AuditEvent.GuardrailFlag) audit.get(0);
        assertThat(flag.caller()).isEqualTo(key("carol"));
        assertThat(flag.route()).isEqualTo("GET /ask");
        assertThat(flag.guardrail()).isEqualTo("no-forbidden");
        assertThat(flag.stage()).isEqualTo(AuditEvent.Stage.REQUEST);
        assertThat(flag.action()).isEqualTo(GuardRail.Action.BLOCK);
    }

    @Test @DisplayName("a streamed answer flagged on the stream's own thread still names the caller and route")
    void streamedFlagKeepsTheCaller() throws Exception {
        start(new Rail("note-secrets", GuardRail.Position.POST_LLM, GuardRail.Action.WARN));
        get("/sse", "dave");

        await(() -> audit.stream().anyMatch(e -> e instanceof AuditEvent.GuardrailFlag));
        var flag = audit.stream().filter(e -> e instanceof AuditEvent.GuardrailFlag)
                .map(AuditEvent.GuardrailFlag.class::cast).findFirst().orElseThrow();
        assertThat(flag.caller()).isEqualTo(key("dave"));
        assertThat(flag.route()).isEqualTo("GET /sse");
        assertThat(flag.stage()).isEqualTo(AuditEvent.Stage.RESPONSE);
        assertThat(flag.action()).isEqualTo(GuardRail.Action.WARN);
    }

    @Test @DisplayName("a sink that throws is skipped; the request and the other sinks are unaffected")
    void failingSink() throws Exception {
        start();
        List<AuditEvent> second = new CopyOnWriteArrayList<>();
        app.audit(event -> { throw new IllegalStateException("sink down"); });
        app.audit(second::add);

        assertThat(get("/ask", "erin").statusCode()).isEqualTo(200);
        await(() -> second.size() == 1);
        assertThat(audit).hasSize(1);
    }

    @Test @DisplayName("captured text (opt-in) names the caller and route, redacted, and stays out of the audit records")
    void capturedTextNamesTheCaller() throws Exception {
        start();
        List<io.cafeai.core.audit.Transcript> kept = new CopyOnWriteArrayList<>();
        app.auditText(io.cafeai.core.audit.TextCapture.to(kept::add).keepFor(Duration.ofDays(7)));
        get("/ask?q=mail+ann%40example.com", "grace");

        await(() -> kept.size() == 1);
        var t = kept.getFirst();
        assertThat(t.caller()).isEqualTo(key("grace"));
        assertThat(t.route()).isEqualTo("GET /ask");
        assertThat(t.prompt()).isEqualTo("mail [EMAIL]");
        assertThat(audit.toString()).doesNotContain("ann@example.com").doesNotContain("[EMAIL]");
    }

    @Test @DisplayName("audit records carry no prompt or answer text")
    void metadataOnly() throws Exception {
        start(new Rail("output-check", GuardRail.Position.POST_LLM, GuardRail.Action.WARN));
        get("/ask?q=my+private+question", "frank");
        await(() -> audit.size() == 2);
        assertThat(audit.toString()).doesNotContain("private").doesNotContain("secret").doesNotContain("Fine so far");
    }
}
