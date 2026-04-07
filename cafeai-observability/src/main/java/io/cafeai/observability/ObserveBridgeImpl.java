package io.cafeai.observability;

import io.cafeai.core.Attributes;
import io.cafeai.core.Locals;
import io.cafeai.core.ai.PromptRequest;
import io.cafeai.core.ai.PromptResponse;
import io.cafeai.core.ai.VisionRequest;
import io.cafeai.core.ai.VisionResponse;
import io.cafeai.core.spi.ObserveBridge;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServiceLoader implementation of {@link ObserveBridge}.
 *
 * <p>Reads the registered {@link ObserveStrategy} from the application locals
 * and dispatches before/after calls to the appropriate implementation.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ObserveBridge}
 */
public final class ObserveBridgeImpl implements ObserveBridge {

    private static final Logger log = LoggerFactory.getLogger(ObserveBridgeImpl.class);

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer(
        "io.cafeai", "0.1.0");

    // Set by CafeAIApp.observe() immediately after bridge is loaded
    private volatile ObserveStrategy strategy = new ConsoleObserveStrategy();

    @Override
    public void setStrategy(Object strategyObj) {
        if (!(strategyObj instanceof ObserveStrategy s)) {
            throw new IllegalArgumentException(
                "Expected an io.cafeai.observability.ObserveStrategy instance, got: " +
                strategyObj.getClass().getName() +
                ". Use ObserveStrategy.console() or ObserveStrategy.otel().");
        }
        this.strategy = s;
        log.info("Observability strategy active: {}", s.getClass().getSimpleName());
    }

    /**
     * Context object passed between beforePrompt and afterPrompt.
     * Carries both the start time (for console) and the OTel span (for otel).
     */
    private record ObserveContext(long startMs, Span span, ObserveStrategy strategy) {}

    @Override
    public Object beforePrompt(PromptRequest request) {
        long startMs = System.currentTimeMillis();

        Span span = null;
        if (strategy instanceof OtelObserveStrategy) {
            span = TRACER.spanBuilder("cafeai.llm.call")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
            if (request.sessionId() != null) {
                span.setAttribute("cafeai.session_id", request.sessionId());
            }
        }

        return new ObserveContext(startMs, span, strategy);
    }

    @Override
    public void afterPrompt(Object ctx, PromptRequest request,
                            PromptResponse response, Throwable error) {
        if (!(ctx instanceof ObserveContext context)) return;

        long latencyMs = System.currentTimeMillis() - context.startMs();

        if (context.strategy() instanceof ConsoleObserveStrategy) {
            writeConsole(request, response, error, latencyMs);
        } else if (context.strategy() instanceof OtelObserveStrategy
                   && context.span() != null) {
            writeSpan(context.span(), request, response, error, latencyMs);
        }
    }

    @Override
    public Object beforeVision(VisionRequest request) {
        long startMs = System.currentTimeMillis();

        Span span = null;
        if (strategy instanceof OtelObserveStrategy) {
            span = TRACER.spanBuilder("cafeai.llm.vision")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
            span.setAttribute("cafeai.vision.mime_type",     request.mimeType());
            span.setAttribute("cafeai.vision.content_bytes", request.content().length);
            if (request.sessionId() != null) {
                span.setAttribute("cafeai.session_id", request.sessionId());
            }
        }

        return new ObserveContext(startMs, span, strategy);
    }

    @Override
    public void afterVision(Object ctx, VisionRequest request,
                            VisionResponse response, Throwable error) {
        if (!(ctx instanceof ObserveContext context)) return;

        long latencyMs = System.currentTimeMillis() - context.startMs();

        if (context.strategy() instanceof ConsoleObserveStrategy) {
            writeVisionConsole(request, response, error, latencyMs);
        } else if (context.strategy() instanceof OtelObserveStrategy
                   && context.span() != null) {
            writeVisionSpan(context.span(), request, response, error, latencyMs);
        }
    }

    // -- Console output --------------------------------------------------------

    private static void writeConsole(PromptRequest request,
                                     PromptResponse response,
                                     Throwable error, long latencyMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n-- LLM Call ------------------------------------------\n");

        if (error != null) {
            sb.append("  ERROR: ").append(error.getClass().getSimpleName())
              .append(": ").append(error.getMessage()).append('\n');
        } else if (response != null) {
            sb.append("  model:      ").append(response.modelId()).append('\n');
            if (request.sessionId() != null) {
                sb.append("  session:    ").append(request.sessionId()).append('\n');
            }
            int total = response.totalTokens();
            sb.append("  tokens:     ")
              .append(response.promptTokens()).append(" prompt + ")
              .append(response.outputTokens()).append(" completion");
            if (total > 0) sb.append(" = ").append(total).append(" total");
            sb.append('\n');
            sb.append(String.format("  latency:    %,dms%n", latencyMs));

            int ragDocs = response.ragDocuments() != null ? response.ragDocuments().size() : 0;
            if (ragDocs > 0) {
                sb.append("  rag docs:   ").append(ragDocs).append(" retrieved\n");
            }
            if (response.fromCache()) {
                sb.append("  cache:      hit\n");
            }
        }
        sb.append("------------------------------------------------------");
        log.info(sb.toString());
    }

    // -- OpenTelemetry span ----------------------------------------------------

    private static void writeSpan(Span span, PromptRequest request,
                                  PromptResponse response,
                                  Throwable error, long latencyMs) {
        try {
            span.setAttribute("cafeai.latency_ms", latencyMs);

            if (error != null) {
                span.setStatus(StatusCode.ERROR, error.getMessage());
                span.setAttribute("cafeai.error", error.getClass().getName());
                return;
            }

            if (response != null) {
                span.setAttribute("cafeai.model",             response.modelId());
                span.setAttribute("cafeai.prompt_tokens",     response.promptTokens());
                span.setAttribute("cafeai.completion_tokens", response.outputTokens());
                span.setAttribute("cafeai.total_tokens",      response.totalTokens());
                span.setAttribute("cafeai.cache_hit",         response.fromCache());

                int ragDocs = response.ragDocuments() != null
                    ? response.ragDocuments().size() : 0;
                span.setAttribute("cafeai.rag_docs_retrieved", ragDocs);
            }
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }
    }


    // -- Vision console output -------------------------------------------------

    private static void writeVisionConsole(VisionRequest request,
                                           VisionResponse response,
                                           Throwable error, long latencyMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n-- Vision Call ----------------------------------------\n");
        sb.append("  mimeType:   ").append(request.mimeType()).append('\n');
        sb.append("  bytes:      ").append(String.format("%,d", request.content().length)).append('\n');

        if (error != null) {
            sb.append("  ERROR: ").append(error.getClass().getSimpleName())
              .append(": ").append(error.getMessage()).append('\n');
        } else if (response != null) {
            sb.append("  model:      ").append(response.modelId()).append('\n');
            if (request.sessionId() != null) {
                sb.append("  session:    ").append(request.sessionId()).append('\n');
            }
            sb.append("  tokens:     ")
              .append(response.promptTokens()).append(" prompt + ")
              .append(response.outputTokens()).append(" completion")
              .append(" = ").append(response.totalTokens()).append(" total\n");
            sb.append(String.format("  latency:    %,dms%n", latencyMs));
        }
        sb.append("------------------------------------------------------");
        log.info(sb.toString());
    }

    // -- Vision OTel span ------------------------------------------------------

    private static void writeVisionSpan(Span span, VisionRequest request,
                                        VisionResponse response,
                                        Throwable error, long latencyMs) {
        try {
            span.setAttribute("cafeai.latency_ms",           latencyMs);
            span.setAttribute("cafeai.vision.mime_type",     request.mimeType());
            span.setAttribute("cafeai.vision.content_bytes", request.content().length);

            if (error != null) {
                span.setStatus(StatusCode.ERROR, error.getMessage());
                span.setAttribute("cafeai.error", error.getClass().getName());
                return;
            }
            if (response != null) {
                span.setAttribute("cafeai.model",             response.modelId());
                span.setAttribute("cafeai.prompt_tokens",     response.promptTokens());
                span.setAttribute("cafeai.completion_tokens", response.outputTokens());
                span.setAttribute("cafeai.total_tokens",      response.totalTokens());
            }
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }
    }

}
