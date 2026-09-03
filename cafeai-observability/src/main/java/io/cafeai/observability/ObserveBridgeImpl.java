package io.cafeai.observability;

import io.cafeai.core.ai.*;
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

    /**
     * OpenTelemetry GenAI semantic-convention keys.
     * @see <a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/">semconv/gen-ai</a>
     */
    private static final class Sem {
        static final String OP        = "gen_ai.operation.name";
        static final String SYSTEM    = "gen_ai.system";
        static final String REQ_MODEL = "gen_ai.request.model";
        static final String RES_MODEL = "gen_ai.response.model";
        static final String IN_TOKENS = "gen_ai.usage.input_tokens";
        static final String OUT_TOKENS = "gen_ai.usage.output_tokens";
        static final String ERROR_TYPE = "error.type";
        // CafeAI extensions (no semconv equivalent)
        static final String LATENCY   = "cafeai.latency_ms";
        static final String SESSION   = "cafeai.session.id";
        static final String CACHE_HIT = "cafeai.cache_hit";
        static final String RAG_DOCS  = "cafeai.rag.documents_retrieved";
        static final String TOTAL_TOK = "cafeai.usage.total_tokens";

        /** Best-effort provider system from a model id; {@code null} when unrecognised. */
        static String system(String modelId) {
            if (modelId == null) return null;
            String m = modelId.toLowerCase();
            if (m.contains("gpt") || m.startsWith("o1") || m.contains("text-embedding")
                    || m.contains("whisper") || m.contains("tts")) return "openai";
            if (m.contains("claude")) return "anthropic";
            if (m.contains("llama") || m.contains("qwen") || m.contains("mistral")
                    || m.contains("gemma") || m.contains("phi")) return "ollama";
            return null;
        }

        static void model(Span span, String modelId) {
            if (modelId == null) return;
            span.setAttribute(RES_MODEL, modelId);
            String sys = system(modelId);
            if (sys != null) span.setAttribute(SYSTEM, sys);
        }

        static void error(Span span, Throwable error) {
            span.setStatus(StatusCode.ERROR, error.getMessage());
            span.setAttribute(ERROR_TYPE, error.getClass().getName());
        }
    }

    @Override
    public Object beforePrompt(PromptRequest request) {
        long startMs = System.currentTimeMillis();

        Span span = null;
        if (strategy instanceof OtelObserveStrategy) {
            span = TRACER.spanBuilder("chat")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
            span.setAttribute(Sem.OP, "chat");
            if (request.sessionId() != null) {
                span.setAttribute(Sem.SESSION, request.sessionId());
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
            span = TRACER.spanBuilder("chat")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
            span.setAttribute(Sem.OP, "chat");
            span.setAttribute("cafeai.input.type",        "vision");
            span.setAttribute("cafeai.input.mime_type",   request.mimeType());
            span.setAttribute("cafeai.input.content_bytes", request.content().length);
            if (request.sessionId() != null) {
                span.setAttribute(Sem.SESSION, request.sessionId());
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
            span.setAttribute(Sem.LATENCY, latencyMs);

            if (error != null) {
                Sem.error(span, error);
                return;
            }

            if (response != null) {
                Sem.model(span, response.modelId());
                span.setAttribute(Sem.IN_TOKENS,  response.promptTokens());
                span.setAttribute(Sem.OUT_TOKENS, response.outputTokens());
                span.setAttribute(Sem.TOTAL_TOK,  response.totalTokens());
                span.setAttribute(Sem.CACHE_HIT,  response.fromCache());

                int ragDocs = response.ragDocuments() != null
                    ? response.ragDocuments().size() : 0;
                span.setAttribute(Sem.RAG_DOCS, ragDocs);
            }
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }
    }


    @Override
    public Object beforeAudio(AudioRequest request) {
        long startMs = System.currentTimeMillis();

        Span span = null;
        if (strategy instanceof OtelObserveStrategy) {
            span = TRACER.spanBuilder("transcribe")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
            span.setAttribute(Sem.OP, "transcribe");
            span.setAttribute("cafeai.input.mime_type",      request.mimeType());
            span.setAttribute("cafeai.input.content_bytes",  request.content().length);
            if (request.sessionId() != null) {
                span.setAttribute(Sem.SESSION, request.sessionId());
            }
        }

        return new ObserveContext(startMs, span, strategy);
    }

    @Override
    public void afterAudio(Object ctx, AudioRequest request,
                           AudioResponse response, Throwable error) {
        if (!(ctx instanceof ObserveContext context)) return;

        long latencyMs = System.currentTimeMillis() - context.startMs();

        if (context.strategy() instanceof ConsoleObserveStrategy) {
            writeAudioConsole(request, response, error, latencyMs);
        } else if (context.strategy() instanceof OtelObserveStrategy
                   && context.span() != null) {
            writeAudioSpan(context.span(), request, response, error, latencyMs);
        }
    }

    // -- Agents (app.agent) --------------------------------------------------
    // The reasoning loop, tool calls, and chat memory are LangChain4j's; this
    // brackets the whole invocation. Token accounting is not available on the
    // agent path, so the trace records name, latency, and outcome only.

    @Override
    public Object beforeAgent(String agentName) {
        Span span = null;
        if (strategy instanceof OtelObserveStrategy) {
            span = TRACER.spanBuilder("invoke_agent " + agentName)
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
            span.setAttribute(Sem.OP, "invoke_agent");
            span.setAttribute("gen_ai.agent.name", agentName);
        }
        return new ObserveContext(System.currentTimeMillis(), span, strategy);
    }

    @Override
    public void afterAgent(Object ctx, String agentName, Throwable error) {
        if (!(ctx instanceof ObserveContext context)) return;
        long latencyMs = System.currentTimeMillis() - context.startMs();

        if (context.strategy() instanceof ConsoleObserveStrategy) {
            StringBuilder sb = new StringBuilder();
            sb.append("\n-- Agent Invocation ---------------------------------\n");
            sb.append("  agent:      ").append(agentName).append('\n');
            if (error != null) {
                sb.append("  ERROR:      ").append(error.getClass().getSimpleName())
                  .append(": ").append(error.getMessage()).append('\n');
            } else {
                sb.append(String.format("  latency:    %,dms%n", latencyMs));
            }
            sb.append("------------------------------------------------------");
            log.info(sb.toString());
        } else if (context.strategy() instanceof OtelObserveStrategy && context.span() != null) {
            try {
                context.span().setAttribute(Sem.LATENCY, latencyMs);
                if (error != null) {
                    Sem.error(context.span(), error);
                } else {
                    context.span().setStatus(StatusCode.OK);
                }
            } finally {
                context.span().end();
            }
        }
    }

    // -- RAG retrieval -----------------------------------------------------------

    @Override
    public Object beforeRetrieval(String query) {
        Span span = null;
        if (strategy instanceof OtelObserveStrategy) {
            span = TRACER.spanBuilder("retrieve")
                .setSpanKind(SpanKind.CLIENT)
                .setParent(Context.current())
                .startSpan();
            span.setAttribute(Sem.OP, "retrieve");
            span.setAttribute("db.system", "vector_db");
            span.setAttribute("cafeai.rag.query_length", query != null ? query.length() : 0);
        }
        return new ObserveContext(System.currentTimeMillis(), span, strategy);
    }

    @Override
    public void afterRetrieval(Object ctx, String query, int documentCount, Throwable error) {
        if (!(ctx instanceof ObserveContext context)) return;
        long latencyMs = System.currentTimeMillis() - context.startMs();

        if (context.strategy() instanceof ConsoleObserveStrategy) {
            log.info("\n-- RAG Retrieval -----------------------------------\n"
                + "  query chars: " + (query != null ? query.length() : 0) + "\n"
                + (error != null
                    ? "  ERROR:       " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n"
                    : "  documents:   " + documentCount + "\n"
                      + String.format("  latency:     %,dms%n", latencyMs))
                + "------------------------------------------------------");
        } else if (context.strategy() instanceof OtelObserveStrategy && context.span() != null) {
            try {
                context.span().setAttribute(Sem.LATENCY, latencyMs);
                context.span().setAttribute(Sem.RAG_DOCS, documentCount);
                if (error != null) Sem.error(context.span(), error);
                else context.span().setStatus(StatusCode.OK);
            } finally {
                context.span().end();
            }
        }
    }

    @Override
    public Object beforeSynthesis(SynthesisRequest request) {
        return new ObserveContext(System.currentTimeMillis(), null, strategy);
    }

    @Override
    public void afterSynthesis(SynthesisRequest request,
                               SynthesisResponse response, Throwable error) {
        if (!(strategy instanceof ConsoleObserveStrategy)) return;

        long latencyMs = response != null ? response.latencyMs() : 0;
        int  bytes     = response != null && response.hasAudio() ? response.audioBytes().length : 0;
        String fmt     = response != null ? response.format() : "unknown";

        StringBuilder sb = new StringBuilder();
        sb.append("\n-- TTS Synthesis -------------------------------------\n");
        sb.append(String.format("  characters: %,d%n", request.text().length()));
        sb.append(String.format("  format:     %s%n", fmt));
        if (response != null) {
            sb.append(String.format("  model:      %s%n", response.modelId()));
            sb.append(String.format("  bytes:      %,d%n", bytes));
        }
        if (error != null) {
            sb.append(String.format("  error:      %s%n", error.getMessage()));
        } else {
            sb.append(String.format("  latency:    %,dms%n", latencyMs));
        }
        sb.append("------------------------------------------------------");
        log.info(sb.toString());
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

    // -- Audio console output --------------------------------------------------

    private static void writeAudioConsole(AudioRequest request,
                                          AudioResponse response,
                                          Throwable error, long latencyMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n-- Audio Call ----------------------------------------\n");
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

    // -- Audio OTel span -------------------------------------------------------

    private static void writeAudioSpan(Span span, AudioRequest request,
                                       AudioResponse response,
                                       Throwable error, long latencyMs) {
        try {
            span.setAttribute(Sem.LATENCY, latencyMs);
            span.setAttribute("cafeai.input.mime_type",     request.mimeType());
            span.setAttribute("cafeai.input.content_bytes", request.content().length);

            if (error != null) {
                Sem.error(span, error);
                return;
            }
            if (response != null) {
                Sem.model(span, response.modelId());
                span.setAttribute(Sem.IN_TOKENS,  response.promptTokens());
                span.setAttribute(Sem.OUT_TOKENS, response.outputTokens());
                span.setAttribute(Sem.TOTAL_TOK,  response.totalTokens());
            }
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }
    }

    // -- Vision OTel span ------------------------------------------------------

    private static void writeVisionSpan(Span span, VisionRequest request,
                                        VisionResponse response,
                                        Throwable error, long latencyMs) {
        try {
            span.setAttribute(Sem.LATENCY, latencyMs);
            span.setAttribute("cafeai.input.type",          "vision");
            span.setAttribute("cafeai.input.mime_type",     request.mimeType());
            span.setAttribute("cafeai.input.content_bytes", request.content().length);

            if (error != null) {
                Sem.error(span, error);
                return;
            }
            if (response != null) {
                Sem.model(span, response.modelId());
                span.setAttribute(Sem.IN_TOKENS,  response.promptTokens());
                span.setAttribute(Sem.OUT_TOKENS, response.outputTokens());
                span.setAttribute(Sem.TOTAL_TOK,  response.totalTokens());
            }
            span.setStatus(StatusCode.OK);
        } finally {
            span.end();
        }
    }

}
