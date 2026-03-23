package io.cafeai.core;

import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.Router;
import io.cafeai.core.ai.*;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.rag.Retriever;
import io.cafeai.core.rag.Source;
import io.cafeai.core.tools.Tool;
import io.cafeai.core.tools.McpServer;
import io.cafeai.core.agents.AgentDefinition;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.observability.ObserveStrategy;
import io.cafeai.core.streaming.StreamHandler;

import java.util.function.BiConsumer;

/**
 * CafeAI — The main application class.
 *
 * <p>Mirrors Express.js pound-for-pound for familiar HTTP routing,
 * while introducing a rich vocabulary of AI-native primitives.
 *
 * <p>Quick start:
 * <pre>{@code
 *   var app = CafeAI.create();
 *
 *   app.ai(OpenAI.gpt4o());
 *   app.memory(MemoryStrategy.mapped());
 *   app.system("You are a helpful assistant...");
 *
 *   app.post("/chat", (req, res) -> {
 *       res.stream(app.prompt(req.body("message")));
 *   });
 *
 *   app.listen(8080);
 * }</pre>
 *
 * @see Router   for HTTP routing primitives (get, post, put, delete, use)
 * @see AiConfig for AI provider configuration
 */
public interface CafeAI extends Router {

    // ── Factory ──────────────────────────────────────────────────────────────

    /**
     * Creates a new CafeAI application instance.
     * Mirrors: {@code const app = express()}
     */
    static CafeAI create() {
        return new CafeAIApp();
    }

    // ── AI Infrastructure ────────────────────────────────────────────────────

    /**
     * Registers the AI provider and model for this application.
     * This declares: "this application is AI-powered."
     *
     * <pre>{@code
     *   app.ai(OpenAI.gpt4o());
     *   app.ai(Anthropic.claude35Sonnet());
     *   app.ai(Ollama.llama3());       // local model
     * }</pre>
     */
    CafeAI ai(AiProvider provider);

    /**
     * Registers a model routing strategy — dispatch cheap vs expensive
     * models based on query complexity. Reduces cost dramatically.
     *
     * <pre>{@code
     *   app.ai(ModelRouter.smart()
     *       .simple(OpenAI.gpt4oMini())
     *       .complex(OpenAI.gpt4o()));
     * }</pre>
     */
    CafeAI ai(ModelRouter router);

    // ── Prompt Primitives ────────────────────────────────────────────────────

    /**
     * Sets the system prompt — the AI's persona, rules, and constraints.
     * System prompts are architecturally distinct from user prompts.
     * They set the stage. They don't change per request.
     *
     * <pre>{@code
     *   app.system("You are a helpful customer service agent for Acme Corp. " +
     *              "You are empathetic, concise, and escalate unresolved issues.");
     * }</pre>
     */
    CafeAI system(String systemPrompt);

    /**
     * Registers a named, reusable prompt template.
     * Templates support variable interpolation via {{variable}} syntax.
     *
     * <pre>{@code
     *   app.template("classify", "Classify the following text: {{input}}");
     *   app.template("summarize", "Summarize in {{words}} words: {{text}}");
     * }</pre>
     */
    CafeAI template(String name, String template);

    // ── Memory ───────────────────────────────────────────────────────────────

    /**
     * Configures the tiered memory strategy for conversation context.
     *
     * <pre>{@code
     *   // Rung 1: In-JVM (prototype)
     *   app.memory(MemoryStrategy.inMemory());
     *
     *   // Rung 2: SSD-backed via Java FFM MemorySegment (production, single node)
     *   app.memory(MemoryStrategy.mapped());
     *
     *   // Rung 3: Chronicle Map off-heap (high-throughput, single node)
     *   app.memory(MemoryStrategy.chronicle());
     *
     *   // Rung 4: Redis (distributed escape valve)
     *   app.memory(MemoryStrategy.redis(config));
     *
     *   // Rung 5: Hybrid — warm SSD + cold Redis
     *   app.memory(MemoryStrategy.hybrid());
     * }</pre>
     */
    CafeAI memory(MemoryStrategy strategy);

    // ── RAG ──────────────────────────────────────────────────────────────────

    /**
     * Attaches a retrieval pipeline. Semantic search is performed
     * automatically on each prompt before the LLM call.
     *
     * <pre>{@code
     *   app.rag(Retriever.semantic(topK(5)));
     *   app.rag(Retriever.hybrid(topK(5)));   // dense + sparse
     * }</pre>
     */
    CafeAI rag(Retriever retriever);

    /**
     * Ingests a knowledge source into the vector store.
     * Documents are chunked, embedded, and indexed automatically.
     *
     * <pre>{@code
     *   app.ingest(Source.pdf("docs/handbook.pdf"));
     *   app.ingest(Source.url("https://docs.example.com"));
     *   app.ingest(Source.directory("knowledge/"));
     *   app.ingest(Source.github("owner/repo"));
     * }</pre>
     */
    CafeAI ingest(Source source);

    /**
     * Registers the vector database for embedding storage and retrieval.
     *
     * <pre>{@code
     *   app.vectordb(PgVector.connect(config));
     *   app.vectordb(Chroma.local());
     *   app.vectordb(VectorStore.inMemory());  // prototype
     * }</pre>
     */
    CafeAI vectordb(Object store);

    /**
     * Configures the embedding model for document and query vectorization.
     *
     * <pre>{@code
     *   app.embed(EmbeddingModel.openAi());     // remote, high quality
     *   app.embed(EmbeddingModel.local());      // local via ONNX/FFM
     * }</pre>
     */
    CafeAI embed(EmbeddingModel model);

    // ── Tools and MCP ────────────────────────────────────────────────────────

    /**
     * Registers a single Java function as an LLM-callable tool.
     * A tool is code YOU wrote. You own its trust level and lifecycle.
     *
     * <pre>{@code
     *   app.tool(OrderLookupTool.create());
     *   app.tool(new CustomerProfileTool(customerService));
     * }</pre>
     */
    CafeAI tool(Tool tool);

    /**
     * Registers a suite of tools via a registry.
     *
     * <pre>{@code
     *   app.tools(ToolRegistry.of(tool1, tool2, tool3));
     * }</pre>
     */
    CafeAI tools(Tool... tools);

    /**
     * Registers an external MCP server as a capability provider.
     * An MCP server is an external contract. Different trust level than a tool.
     *
     * <pre>{@code
     *   app.mcp(McpServer.github());
     *   app.mcp(McpServer.connect("http://my-mcp-server:3000"));
     * }</pre>
     */
    CafeAI mcp(McpServer server);

    // ── Chaining ─────────────────────────────────────────────────────────────

    /**
     * Defines a named, composable pipeline of steps.
     * Chains are themselves middleware-composable — recursive by design.
     *
     * <pre>{@code
     *   app.chain("classify-and-respond",
     *       Steps.classify(),
     *       Steps.route(),
     *       Steps.respond());
     * }</pre>
     */
    CafeAI chain(String name, ChainStep... steps);

    // ── Guardrails ───────────────────────────────────────────────────────────

    /**
     * Attaches a guardrail to the pipeline.
     * Guardrails are middleware — they intercept pre-LLM and/or post-LLM.
     *
     * <pre>{@code
     *   app.guard(GuardRail.pii());
     *   app.guard(GuardRail.jailbreak());
     *   app.guard(GuardRail.bias());
     *   app.guard(GuardRail.hallucination());
     *   app.guard(myCustomGuard);
     * }</pre>
     */
    CafeAI guard(GuardRail guardRail);

    // ── Agents ───────────────────────────────────────────────────────────────

    /**
     * Registers a named agent definition.
     * Agents run in isolated StructuredTaskScope — failures are contained.
     *
     * <pre>{@code
     *   app.agent("classifier", AgentDefinition.react()
     *       .tools(classifyTool)
     *       .maxIterations(5));
     * }</pre>
     */
    CafeAI agent(String name, AgentDefinition definition);

    /**
     * Declares a multi-agent orchestration topology.
     * The orchestrator delegates to specialist sub-agents via Structured Concurrency.
     *
     * <pre>{@code
     *   app.orchestrate("support-pipeline",
     *       "classifier",
     *       "knowledge-retriever",
     *       "response-generator");
     * }</pre>
     */
    CafeAI orchestrate(String name, String... agentNames);

    // ── Observability ────────────────────────────────────────────────────────

    /**
     * Attaches an observability strategy.
     * Every LLM call becomes a traced, measured, scored event.
     *
     * <pre>{@code
     *   app.observe(ObserveStrategy.otel());
     *   app.observe(ObserveStrategy.console());  // development
     * }</pre>
     */
    CafeAI observe(ObserveStrategy strategy);

    /**
     * Attaches an eval harness for scoring retrieval and response quality.
     *
     * <pre>{@code
     *   app.eval(EvalHarness.defaults());
     * }</pre>
     */
    CafeAI eval(Object harness);

    // ── Server Lifecycle ─────────────────────────────────────────────────────

    /**
     * Starts the server on the given port.
     * Mirrors: {@code app.listen(3000)}
     */
    void listen(int port);

    /**
     * Starts the server on the given port with a startup callback.
     * Mirrors: {@code app.listen(3000, () => console.log('Running'))}
     */
    void listen(int port, Runnable onStart);
}
