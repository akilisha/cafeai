package io.cafeai.observability;

/**
 * Observation strategy -- controls how every LLM call is traced and measured.
 *
 * <p>Register via {@code app.observe(ObserveStrategy.otel())} at startup.
 * Once registered, every {@code app.prompt().call()} produces an observation
 * automatically -- no changes to application code required.
 *
 * <pre>{@code
 *   // Development -- readable per-call output to the console
 *   app.observe(ObserveStrategy.console());
 *
 *   // Production -- OpenTelemetry spans exported to your collector
 *   app.observe(ObserveStrategy.otel());
 * }</pre>
 */
public interface ObserveStrategy {

    /**
     * Console observation strategy -- structured, human-readable output
     * per LLM call. Designed for development and local debugging.
     *
     * <p>Output format per call:
     * <pre>
     * -- LLM Call ----------------------------------
     *   model:      gpt-4o
     *   session:    abc123
     *   tokens:     241 prompt + 87 completion = 328 total
     *   latency:    1,243ms
     *   rag docs:   3 retrieved
     * ----------------------------------------------
     * </pre>
     */
    static ObserveStrategy console() {
        return new ConsoleObserveStrategy();
    }

    /**
     * OpenTelemetry observation strategy -- exports a span per LLM call
     * with standardised attributes.
     *
     * <p>Spans are named for the operation ({@code chat}, {@code transcribe}, {@code retrieve},
     * {@code invoke_agent <name>}). Span attributes follow the OpenTelemetry GenAI conventions:
     * <ul>
     *   <li>{@code gen_ai.operation.name} -- the operation</li>
     *   <li>{@code gen_ai.response.model} -- model ID</li>
     *   <li>{@code gen_ai.system} -- provider, inferred from the model ID when recognised</li>
     *   <li>{@code gen_ai.usage.input_tokens}, {@code gen_ai.usage.output_tokens} -- token usage</li>
     *   <li>{@code cafeai.usage.total_tokens} -- total tokens consumed</li>
     *   <li>{@code cafeai.latency_ms} -- wall-clock latency</li>
     *   <li>{@code cafeai.session.id} -- session ID if present</li>
     *   <li>{@code cafeai.rag.documents_retrieved} -- number of RAG documents</li>
     *   <li>{@code cafeai.cache_hit} -- whether the semantic cache answered</li>
     *   <li>{@code error.type} -- exception class name if the call failed (status is also ERROR)</li>
     * </ul>
     *
     * <p>Uses the global {@code OpenTelemetry} instance. Configure your
     * exporter (OTLP, Jaeger, Zipkin, etc.) via standard OTel SDK configuration
     * -- CafeAI does not manage the OTel SDK lifecycle.
     */
    static ObserveStrategy otel() {
        return new OtelObserveStrategy();
    }
}
