package io.cafeai.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.cafeai.core.ai.AudioRequest;
import io.cafeai.core.ai.AudioResponse;
import io.cafeai.core.ai.PromptRequest;
import io.cafeai.core.ai.PromptResponse;
import io.cafeai.core.ai.VisionRequest;
import io.cafeai.core.ai.VisionResponse;
import io.cafeai.core.rag.RagDocument;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What {@link ObserveBridgeImpl} records for each kind of call: a span under the OpenTelemetry
 * strategy (real spans, read back from an in-memory exporter) and a log block under the console
 * strategy. The attribute names asserted here are the ones the code writes; the docs list the same.
 */
@DisplayName("ObserveBridgeImpl")
class ObserveBridgeTest {

    private ObserveBridgeImpl bridge;

    @BeforeEach
    void setUp() {
        TestOtel.reset();                     // also initialises the global SDK before the bridge loads
        bridge = new ObserveBridgeImpl();
    }

    private static PromptRequest prompt(String session) {
        PromptRequest request = new PromptRequest("hello", r -> null);
        return session == null ? request : request.session(session);
    }

    private static PromptResponse.Builder answer(String model) {
        return PromptResponse.builder().text("hi").modelId(model).promptTokens(11).outputTokens(7);
    }

    // -- OpenTelemetry ------------------------------------------------------------------

    @Nested @DisplayName("under the OpenTelemetry strategy")
    class Otel {

        @BeforeEach
        void otel() { bridge.setStrategy(ObserveStrategy.otel()); }

        @Test @DisplayName("a prompt is a CLIENT span named 'chat' carrying model, tokens, latency and session")
        void promptSpan() {
            PromptRequest request = prompt("s-1");
            Object ctx = bridge.beforePrompt(request);
            bridge.afterPrompt(ctx, request, answer("gpt-4o").build(), null);

            SpanData span = TestOtel.onlySpan();
            assertThat(span.getName()).isEqualTo("chat");
            assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
            Map<String, Object> a = TestOtel.attrs(span);
            assertThat(a).containsEntry("gen_ai.operation.name", "chat")
                .containsEntry("gen_ai.response.model", "gpt-4o")
                .containsEntry("gen_ai.system", "openai")
                .containsEntry("gen_ai.usage.input_tokens", 11L)
                .containsEntry("gen_ai.usage.output_tokens", 7L)
                .containsEntry("cafeai.usage.total_tokens", 18L)
                .containsEntry("cafeai.cache_hit", false)
                .containsEntry("cafeai.rag.documents_retrieved", 0L)
                .containsEntry("cafeai.session.id", "s-1");
            assertThat((Long) a.get("cafeai.latency_ms")).isGreaterThanOrEqualTo(0L);
        }

        @Test @DisplayName("retrieved documents and a cache hit are recorded")
        void ragAndCache() {
            PromptRequest request = prompt(null);
            Object ctx = bridge.beforePrompt(request);
            bridge.afterPrompt(ctx, request, answer("gpt-4o").fromCache(true)
                .ragDocuments(List.of(new RagDocument("a", "s1", 1.0), new RagDocument("b", "s2", 0.9))).build(), null);

            Map<String, Object> a = TestOtel.attrs(TestOtel.onlySpan());
            assertThat(a).containsEntry("cafeai.cache_hit", true).containsEntry("cafeai.rag.documents_retrieved", 2L);
            assertThat(a).as("no session was set").doesNotContainKey("cafeai.session.id");
        }

        @Test @DisplayName("a failed call sets ERROR status and the exception type, and no token counts")
        void promptError() {
            PromptRequest request = prompt(null);
            Object ctx = bridge.beforePrompt(request);
            bridge.afterPrompt(ctx, request, null, new IllegalStateException("model unavailable"));

            SpanData span = TestOtel.onlySpan();
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            Map<String, Object> a = TestOtel.attrs(span);
            assertThat(a).containsEntry("error.type", "java.lang.IllegalStateException")
                .doesNotContainKey("gen_ai.usage.input_tokens");
        }

        @Test @DisplayName("gen_ai.system is inferred from the model id, and left out when it is not recognised")
        void systemInference() {
            for (String[] c : new String[][] { {"claude-sonnet-4-5", "anthropic"}, {"llama3.3", "ollama"},
                                               {"gpt-4o-mini", "openai"}, {"nvidia/nemotron-3.5", null} }) {
                TestOtel.reset();
                PromptRequest request = prompt(null);
                bridge.afterPrompt(bridge.beforePrompt(request), request, answer(c[0]).build(), null);
                Map<String, Object> a = TestOtel.attrs(TestOtel.onlySpan());
                if (c[1] == null) assertThat(a).as(c[0]).doesNotContainKey("gen_ai.system");
                else assertThat(a).as(c[0]).containsEntry("gen_ai.system", c[1]);
            }
        }

        @Test @DisplayName("a vision call records the input type, mime type and size")
        void visionSpan() {
            VisionRequest request = new VisionRequest("what is this?", new byte[]{1, 2, 3, 4}, "image/png", r -> null)
                .session("v-1");
            Object ctx = bridge.beforeVision(request);
            bridge.afterVision(ctx, request,
                VisionResponse.builder().text("a cat").modelId("gpt-4o").promptTokens(20).outputTokens(5).build(), null);

            SpanData span = TestOtel.onlySpan();
            assertThat(span.getName()).isEqualTo("chat");
            assertThat(TestOtel.attrs(span)).containsEntry("cafeai.input.type", "vision")
                .containsEntry("cafeai.input.mime_type", "image/png")
                .containsEntry("cafeai.input.content_bytes", 4L)
                .containsEntry("gen_ai.usage.input_tokens", 20L)
                .containsEntry("cafeai.session.id", "v-1");
        }

        @Test @DisplayName("an audio call is a 'transcribe' span with the input mime type and size")
        void audioSpan() {
            AudioRequest request = new AudioRequest("transcribe", new byte[]{9, 9}, "audio/mpeg", r -> null);
            Object ctx = bridge.beforeAudio(request);
            bridge.afterAudio(ctx, request,
                AudioResponse.builder().text("hello").modelId("whisper-1").promptTokens(3).outputTokens(2).build(), null);

            SpanData span = TestOtel.onlySpan();
            assertThat(span.getName()).isEqualTo("transcribe");
            assertThat(TestOtel.attrs(span)).containsEntry("gen_ai.operation.name", "transcribe")
                .containsEntry("cafeai.input.mime_type", "audio/mpeg")
                .containsEntry("cafeai.input.content_bytes", 2L)
                .containsEntry("gen_ai.response.model", "whisper-1");
        }

        @Test @DisplayName("an agent invocation is an 'invoke_agent <name>' span")
        void agentSpan() {
            Object ctx = bridge.beforeAgent("triage");
            bridge.afterAgent(ctx, "triage", null);

            SpanData span = TestOtel.onlySpan();
            assertThat(span.getName()).isEqualTo("invoke_agent triage");
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
            assertThat(TestOtel.attrs(span)).containsEntry("gen_ai.agent.name", "triage")
                .containsEntry("gen_ai.operation.name", "invoke_agent");
        }

        @Test @DisplayName("a failed agent invocation is marked ERROR")
        void agentError() {
            Object ctx = bridge.beforeAgent("triage");
            bridge.afterAgent(ctx, "triage", new RuntimeException("tool failed"));

            assertThat(TestOtel.onlySpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        }

        @Test @DisplayName("a RAG retrieval is a 'retrieve' span with the query length and document count")
        void retrievalSpan() {
            Object ctx = bridge.beforeRetrieval("how do refunds work?");
            bridge.afterRetrieval(ctx, "how do refunds work?", 3, null);

            SpanData span = TestOtel.onlySpan();
            assertThat(span.getName()).isEqualTo("retrieve");
            assertThat(TestOtel.attrs(span)).containsEntry("db.system", "vector_db")
                .containsEntry("cafeai.rag.query_length", 20L)
                .containsEntry("cafeai.rag.documents_retrieved", 3L);
        }

        @Test @DisplayName("a failed retrieval is marked ERROR")
        void retrievalError() {
            Object ctx = bridge.beforeRetrieval("q");
            bridge.afterRetrieval(ctx, "q", 0, new IllegalStateException("store down"));

            SpanData span = TestOtel.onlySpan();
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(TestOtel.attrs(span)).containsEntry("error.type", "java.lang.IllegalStateException");
        }

        @Test @DisplayName("a context that did not come from this bridge is ignored, not thrown on")
        void foreignContext() {
            PromptRequest request = prompt(null);
            bridge.afterPrompt(null, request, answer("gpt-4o").build(), null);
            bridge.afterPrompt("not a context", request, answer("gpt-4o").build(), null);
            bridge.afterAgent(null, "x", null);
            bridge.afterRetrieval(null, "q", 1, null);

            assertThat(TestOtel.spans()).isEmpty();
        }
    }

    // -- console ---------------------------------------------------------------------------

    @Nested @DisplayName("under the console strategy")
    class Console {

        private ListAppender<ILoggingEvent> logs;
        private Logger logger;

        @BeforeEach
        void capture() {
            logger = (Logger) LoggerFactory.getLogger(ObserveBridgeImpl.class);
            logs = new ListAppender<>();
            logs.start();
            logger.addAppender(logs);
            bridge.setStrategy(ObserveStrategy.console());
            logs.list.clear();                // drop the "strategy active" line
        }

        @AfterEach
        void release() { logger.detachAppender(logs); }

        private String output() {
            return String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        }

        @Test @DisplayName("a prompt logs model, session, tokens and latency, and creates no span")
        void promptBlock() {
            PromptRequest request = prompt("s-9");
            bridge.afterPrompt(bridge.beforePrompt(request), request, answer("gpt-4o").build(), null);

            assertThat(output()).contains("LLM Call").contains("model:      gpt-4o").contains("session:    s-9")
                .contains("11 prompt + 7 completion = 18 total").contains("latency:");
            assertThat(TestOtel.spans()).isEmpty();
        }

        @Test @DisplayName("retrieved documents and a cache hit are shown")
        void ragAndCache() {
            PromptRequest request = prompt(null);
            bridge.afterPrompt(bridge.beforePrompt(request), request, answer("gpt-4o").fromCache(true)
                .ragDocuments(List.of(new RagDocument("a", "s", 1.0))).build(), null);

            assertThat(output()).contains("rag docs:   1 retrieved").contains("cache:      hit");
        }

        @Test @DisplayName("a failure is shown with its type and message")
        void errorBlock() {
            PromptRequest request = prompt(null);
            bridge.afterPrompt(bridge.beforePrompt(request), request, null, new IllegalStateException("boom"));

            assertThat(output()).contains("ERROR: IllegalStateException: boom");
        }

        @Test @DisplayName("vision, audio, agent and retrieval each log their own block")
        void otherBlocks() {
            VisionRequest v = new VisionRequest("look", new byte[]{1, 2}, "image/png", r -> null);
            bridge.afterVision(bridge.beforeVision(v), v, VisionResponse.builder().modelId("gpt-4o").build(), null);
            AudioRequest au = new AudioRequest("listen", new byte[]{1}, "audio/wav", r -> null);
            bridge.afterAudio(bridge.beforeAudio(au), au, AudioResponse.builder().modelId("whisper-1").build(), null);
            bridge.afterAgent(bridge.beforeAgent("triage"), "triage", null);
            bridge.afterRetrieval(bridge.beforeRetrieval("q"), "q", 4, null);

            assertThat(output()).contains("Vision Call").contains("Audio Call").contains("Agent Invocation")
                .contains("agent:      triage").contains("RAG Retrieval").contains("documents:   4");
            assertThat(TestOtel.spans()).isEmpty();
        }
    }

    // -- strategy selection -----------------------------------------------------------------

    @Test @DisplayName("the default strategy is the console: no span is created until otel() is chosen")
    void defaultIsConsole() {
        PromptRequest request = prompt(null);
        bridge.afterPrompt(bridge.beforePrompt(request), request, answer("gpt-4o").build(), null);

        assertThat(TestOtel.spans()).isEmpty();
    }

    @Test @DisplayName("a strategy that is not an ObserveStrategy is refused with the way to fix it")
    void wrongStrategyType() {
        assertThatThrownBy(() -> bridge.setStrategy("console"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ObserveStrategy.console()").hasMessageContaining("ObserveStrategy.otel()");
    }
}
