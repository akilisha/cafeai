package io.cafeai.core.internal;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Pricing;
import io.cafeai.core.ai.UsageReport;
import io.cafeai.core.audit.AuditEvent;
import io.cafeai.core.audit.AuditSink;
import io.cafeai.core.audit.TextCapture;
import io.cafeai.core.audit.Transcript;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.spi.ObserveBridge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Counts every model call an app makes -- tokens and cost -- and credits it to the route
 * of the HTTP request that made it. Behind {@code app.pricing(...)}, {@code app.usage()}
 * and the usage metrics.
 *
 * <p>Models are wrapped where the app gets them, so prompts, streams, vision, summaries
 * and agents all count. A call's usage goes to the current request's tally (found through
 * a thread-local the app sets while a filter or handler runs; a stream captures it when it
 * starts); the tally is credited to the request's route once the response is sent, when
 * the route that matched is known.
 */
final class UsageMeter {

    /** Whether responses carry an {@code X-CafeAI-Usage} header: development aid. */
    static final ConfigKey<Boolean> HEADER = ConfigKey.of(
        "cafeai.usage.header", Boolean.class, false,
        "Adds an X-CafeAI-Usage header (model calls, tokens, cost) to responses whose request made model calls; for development.");

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    /** The request a filter or handler is running for. */
    record Scope(UsageMeter meter, HelidonRequest req, HelidonResponse res) { }

    /** One model call: when, for whom ({@code null} when anonymous), and its tokens. */
    private record Call(Instant at, Identity.Key caller, String model, long in, long out) { }

    private static final class Totals {
        final LongAdder calls = new LongAdder();
        final LongAdder in = new LongAdder();
        final LongAdder out = new LongAdder();
        final DoubleAdder cost = new DoubleAdder();
        final LongAdder unpriced = new LongAdder();
    }

    /** One request's calls, credited when its response is sent. */
    private static final class Tally {
        final List<Call> calls = new ArrayList<>();
        boolean flushed;
    }

    private final Map<String, Totals> routes = new ConcurrentHashMap<>();
    private final Map<Identity.Key, Totals> callers = new ConcurrentHashMap<>();
    private final Supplier<ObserveBridge> observe;
    private final AuditSink audit;
    private final boolean header = AppConfig.load().get(HEADER);
    private volatile Pricing pricing;
    private volatile TextCapture capture;

    UsageMeter(Supplier<ObserveBridge> observe, AuditSink audit) {
        this.observe = observe;
        this.audit = audit;
    }

    void pricing(Pricing pricing) {
        this.pricing = pricing;
    }

    /** Captures what is asked and answered in every model call, as {@code capture} says ({@code app.auditText}). */
    void capture(TextCapture capture) {
        this.capture = capture;
    }

    /**
     * The caller's last message and the model's answer, redacted, to the transcript sink, when
     * the app captures text. A sink that fails is logged and skipped: it never fails the call.
     */
    private void transcribe(Scope scope, AiProvider provider, List<ChatMessage> messages, ChatResponse response) {
        TextCapture c = capture;
        if (c == null) return;
        try {
            String prompt = lastUserText(messages);
            String answer = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
            String model = provider.modelId() != null ? provider.modelId()
                         : response != null && response.modelName() != null ? response.modelName() : provider.name();
            Instant now = Instant.now();
            c.sink().record(new Transcript(now,
                    scope == null ? null : scope.req().identity().map(Identity::key).orElse(null),
                    scope == null ? UsageReport.NO_REQUEST : routeOf(scope.req()),
                    model,
                    prompt == null ? null : c.redactor().redact(prompt),
                    answer == null ? null : c.redactor().redact(answer),
                    now.plus(c.keepFor())));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(UsageMeter.class)
                    .warn("A transcript sink failed; this transcript is skipped: {}", e.toString());
        }
    }

    /** The text of the last message the caller sent, or {@code null}. */
    private static String lastUserText(List<ChatMessage> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                StringBuilder text = new StringBuilder();
                user.contents().forEach(part -> {
                    if (part instanceof TextContent t) text.append(text.isEmpty() ? "" : "\n").append(t.text());
                });
                return text.isEmpty() ? null : text.toString();
            }
        }
        return null;
    }

    // -- scope ---------------------------------------------------------------------------

    /** A scope that closes without checked exceptions. */
    interface Entered extends AutoCloseable {
        @Override void close();
    }

    /** Marks this thread as working for {@code req} until the returned scope is closed. */
    Entered enter(HelidonRequest req, HelidonResponse res) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(this, req, res));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /**
     * This thread's request scope, to carry onto another thread with {@link #enter(Scope)}:
     * work a request hands to its own thread (a stream) is still that request's work.
     */
    Scope capture() {
        return scope();
    }

    /** Marks this thread as working for a captured scope; nothing to do when it is {@code null}. */
    Entered enter(Scope captured) {
        return enterScope(captured);
    }

    /** Whatever request scope this thread is in, of any app, or {@code null}. */
    static Scope currentScope() {
        return CURRENT.get();
    }

    /** Puts this thread in a captured scope until closed; nothing to do when it is {@code null}. */
    static Entered enterScope(Scope captured) {
        if (captured == null) return () -> { };
        Scope previous = CURRENT.get();
        CURRENT.set(captured);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /** The request this thread is running a filter or handler for, or {@code null}. */
    static HelidonRequest currentRequest() {
        Scope s = CURRENT.get();
        return s == null ? null : s.req();
    }

    private Scope scope() {
        Scope s = CURRENT.get();
        return s != null && s.meter() == this ? s : null;
    }

    // -- recording -----------------------------------------------------------------------

    private void record(Scope scope, AiProvider provider, ChatResponse response) {
        TokenUsage usage = response == null ? null : response.tokenUsage();
        long in = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        long out = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        String model = provider.modelId() != null ? provider.modelId()
                     : response != null && response.modelName() != null ? response.modelName() : provider.name();
        Identity.Key caller = scope == null ? null
                : scope.req().identity().map(Identity::key).orElse(null);
        Call call = new Call(Instant.now(), caller, model, in, out);

        if (scope == null) {
            credit(UsageReport.NO_REQUEST, List.of(call));
            return;
        }
        Tally tally;
        boolean first = false;
        synchronized (scope.req()) {
            tally = scope.req().attribute("_usage", Tally.class);
            if (tally == null) {
                tally = new Tally();
                scope.req().setAttribute("_usage", tally);
                first = true;
            }
            if (tally.flushed) {
                // The response already went out (a late stream completion): credit now.
                credit(routeOf(scope.req()), List.of(call));
                return;
            }
            tally.calls.add(call);
        }
        if (first) {
            Tally t = tally;
            if (header) scope.res().beforeSend(() -> scope.res().set("X-CafeAI-Usage", headerValue(scope.req(), t)));
            if (scope.res().helidonServerResponse().isSent()) flush(scope.req(), t);
            else scope.res().helidonServerResponse().whenSent(() -> flush(scope.req(), t));
        }
    }

    private void flush(HelidonRequest req, Tally tally) {
        List<Call> calls;
        synchronized (req) {
            if (tally.flushed) return;
            tally.flushed = true;
            calls = List.copyOf(tally.calls);
        }
        credit(routeOf(req), calls);
    }

    private void credit(String route, List<Call> calls) {
        ObserveBridge bridge = observe.get();
        for (Call c : calls) {
            OptionalDouble cost = pricing == null ? OptionalDouble.empty() : pricing.cost(c.model(), c.in(), c.out());
            Double dollars = cost.isPresent() ? cost.getAsDouble() : null;
            if (bridge != null) bridge.onUsage(route, c.model(), c.in(), c.out(), dollars);
            audit.record(new AuditEvent.ModelCall(c.at(), c.caller(), route, c.model(), c.in(), c.out(), dollars));
            // Totals last: once app.usage() shows a call, its metric and audit record exist too.
            if (c.caller() != null) add(callers.computeIfAbsent(c.caller(), k -> new Totals()), c, cost);
            add(routes.computeIfAbsent(route, r -> new Totals()), c, cost);
        }
    }

    private static void add(Totals totals, Call c, OptionalDouble cost) {
        totals.calls.increment();
        totals.in.add(c.in());
        totals.out.add(c.out());
        if (cost.isPresent()) totals.cost.add(cost.getAsDouble());
        else totals.unpriced.increment();
    }

    private static String routeOf(HelidonRequest req) {
        Object pattern = req.attribute("_routePattern");
        return req.method() + " " + (pattern != null ? pattern : "(unmatched)");
    }

    private String headerValue(HelidonRequest req, Tally tally) {
        long in = 0, out = 0, unpriced = 0;
        double cost = 0;
        List<Call> calls;
        synchronized (req) { calls = List.copyOf(tally.calls); }
        for (Call c : calls) {
            in += c.in();
            out += c.out();
            OptionalDouble price = pricing == null ? OptionalDouble.empty() : pricing.cost(c.model(), c.in(), c.out());
            if (price.isPresent()) cost += price.getAsDouble();
            else unpriced++;
        }
        return String.format(Locale.ROOT, "calls=%d; in=%d; out=%d; cost=%s", calls.size(), in, out,
                unpriced > 0 ? "unknown" : String.format(Locale.ROOT, "$%.6f", cost));
    }

    UsageReport report() {
        List<UsageReport.RouteUsage> list = new ArrayList<>();
        routes.forEach((route, t) -> list.add(new UsageReport.RouteUsage(route,
                t.calls.sum(), t.in.sum(), t.out.sum(), t.cost.sum(), t.unpriced.sum())));
        List<UsageReport.CallerUsage> byCaller = new ArrayList<>();
        callers.forEach((caller, t) -> byCaller.add(new UsageReport.CallerUsage(caller,
                t.calls.sum(), t.in.sum(), t.out.sum(), t.cost.sum(), t.unpriced.sum())));
        return new UsageReport(list, byCaller);
    }

    // -- the wrapped models --------------------------------------------------------------

    ChatModel meter(ChatModel model, AiProvider provider) {
        return new MeteredChatModel(model, provider);
    }

    /**
     * A streaming model is wrapped per call, on the request's thread, while the stream itself
     * may start on another: the request in scope here is the one the stream belongs to.
     * (A chat model is not captured this way: an agent keeps one model across many requests.)
     */
    StreamingChatModel meter(StreamingChatModel model, AiProvider provider) {
        return new MeteredStreamingModel(model, provider, scope());
    }

    private final class MeteredChatModel implements ChatModel {
        private final ChatModel delegate;
        private final AiProvider provider;

        MeteredChatModel(ChatModel delegate, AiProvider provider) {
            this.delegate = delegate;
            this.provider = provider;
        }

        // Each overload goes to the same overload on the model: a model may implement any one of them.
        @Override
        public ChatResponse chat(ChatRequest request) {
            return recorded(request.messages(), delegate.chat(request));
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return recorded(messages, delegate.chat(messages));
        }

        @Override
        public ChatResponse chat(ChatMessage... messages) {
            return recorded(List.of(messages), delegate.chat(messages));
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            return recorded(request.messages(), delegate.chat(request));
        }

        private ChatResponse recorded(List<ChatMessage> messages, ChatResponse response) {
            Scope scope = scope();
            record(scope, provider, response);
            transcribe(scope, provider, messages, response);
            return response;
        }

        @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
        @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
        @Override public ModelProvider provider() { return delegate.provider(); }
    }

    private final class MeteredStreamingModel implements StreamingChatModel {
        private final StreamingChatModel delegate;
        private final AiProvider provider;
        private final Scope createdIn;

        MeteredStreamingModel(StreamingChatModel delegate, AiProvider provider, Scope createdIn) {
            this.delegate = delegate;
            this.provider = provider;
            this.createdIn = createdIn;
        }

        // Each overload goes to the same overload on the model: a model may implement any one of them.
        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            delegate.chat(request, metered(request.messages(), handler));
        }

        @Override
        public void chat(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
            delegate.chat(messages, metered(messages, handler));
        }

        @Override
        public void chat(String message, StreamingChatResponseHandler handler) {
            delegate.chat(message, metered(List.of(UserMessage.from(message)), handler));
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            delegate.chat(request, metered(request.messages(), handler));
        }

        private StreamingChatResponseHandler metered(List<ChatMessage> messages, StreamingChatResponseHandler handler) {
            Scope current = scope();
            Scope scope = current != null ? current : createdIn;   // completes on another thread
            return new StreamingChatResponseHandler() {
                @Override public void onPartialResponse(String text) { handler.onPartialResponse(text); }
                @Override public void onPartialThinking(PartialThinking thinking) { handler.onPartialThinking(thinking); }
                @Override public void onPartialToolCall(PartialToolCall call) { handler.onPartialToolCall(call); }
                @Override public void onCompleteToolCall(CompleteToolCall call) { handler.onCompleteToolCall(call); }
                @Override public void onCompleteResponse(ChatResponse response) {
                    record(scope, provider, response);
                    transcribe(scope, provider, messages, response);
                    handler.onCompleteResponse(response);
                }
                @Override public void onError(Throwable error) { handler.onError(error); }
            };
        }

        @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
        @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
        @Override public ModelProvider provider() { return delegate.provider(); }
    }
}
