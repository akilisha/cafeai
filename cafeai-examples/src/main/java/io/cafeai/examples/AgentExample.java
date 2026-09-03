package io.cafeai.examples;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Jlama;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;

import java.util.Map;

/**
 * AgentExample — a supervisor agent that delegates to a specialist sub-agent.
 *
 * <p>Two agents, both registered with {@code app.agent(name, Class)}:
 * <ul>
 *   <li><b>support</b> — the front desk. It owns the conversation and decides
 *       when it needs order details.</li>
 *   <li><b>order-narrator</b> — a specialist that turns a raw order record into
 *       one customer-friendly sentence. The supervisor never talks to it
 *       directly; it reaches it through the {@code orderStatus} {@code @Tool}.</li>
 * </ul>
 *
 * <p>CafeAI supplies the HTTP identity — a name, per-session memory
 * ({@code X-Session-Id} threads the conversation), and a POST_LLM guardrail.
 * LangChain4j owns the reasoning loop, the tool dispatch, and the chat memory
 * window. {@code app.agent("support", SupportAgent.class, session)} returns
 * LangChain4j's own {@code AiService} proxy — no wrapper.
 *
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.AgentExample
 *
 *   curl -H 'Content-Type: application/json' -H 'X-Session-Id: alice' \
 *        -d '{"message":"where is order A-1001?"}' http://localhost:8080/support
 *
 *   curl -H 'Content-Type: application/json' -H 'X-Session-Id: alice' \
 *        -d '{"message":"and what about A-2002?"}' http://localhost:8080/support
 * </pre>
 *
 * <p>Tool-calling reliability scales with model size. Qwen2.5-0.5B is enough to
 * show the wiring; for dependable dispatch use {@code Jlama.mistral()} or an
 * {@code OpenAI} / {@code Anthropic} provider. Jlama needs the run args
 * {@code --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED},
 * set by the {@code cafeai-examples} build.
 */
public class AgentExample {

    /** The supervisor. Owns the conversation; calls a tool when it needs order data. */
    interface SupportAgent {
        @SystemMessage("""
            You are the front-desk support agent for the Acme Coffee Club.
            When a member asks about an order, call the orderStatus tool with the
            order ID and relay what it returns. Keep replies to two sentences.
            Never invent an order status.""")
        String handle(@UserMessage String question);
    }

    /** The specialist sub-agent. Rewrites a raw record as one friendly line. */
    interface OrderNarrator {
        @SystemMessage("""
            Rewrite the raw order record you are given as a single friendly
            sentence a customer would understand. Do not add information.""")
        String narrate(@UserMessage String rawRecord);
    }

    /** The {@code @Tool} the supervisor calls. It delegates to the OrderNarrator agent. */
    static final class OrderDesk {
        private final OrderNarrator narrator;

        OrderDesk(OrderNarrator narrator) {
            this.narrator = narrator;
        }

        @Tool("Look up the delivery status of a coffee order by its ID (e.g. A-1001)")
        String orderStatus(String orderId) {
            String record = FAKE_DB.get(orderId);
            if (record == null) {
                return "No order found with ID " + orderId + ".";
            }
            return narrator.narrate(record);
        }
    }

    private static final Map<String, String> FAKE_DB = Map.of(
        "A-1001", "order=A-1001; item=Ethiopia Yirgacheffe 1kg; status=SHIPPED; carrier=DHL; eta=2026-09-04",
        "A-2002", "order=A-2002; item=Colombia Supremo 500g; status=PACKING; eta=2026-09-06"
    );

    public static void main(String[] args) {
        var app = CafeAI.create();

        app.ai(Jlama.qwen2());
        app.memory(MemoryStrategy.inMemory());

        // Specialist sub-agent — register, then resolve a stateless handle for the tool.
        app.agent("order-narrator", OrderNarrator.class);
        OrderNarrator narrator = app.agent("order-narrator", OrderNarrator.class, null);

        // Supervisor — session memory + a POST_LLM guardrail, plus the delegating tool.
        app.agent("support", SupportAgent.class)
           .memory(MemoryStrategy.inMemory())
           .guard(GuardRail.pii())
           .tool(new OrderDesk(narrator));

        app.post("/support", (req, res, next) -> {
            String message = req.body("message");
            String session = req.header("X-Session-Id");
            SupportAgent agent = app.agent("support", SupportAgent.class, session);
            res.json(Map.of("answer", agent.handle(message)));
        });

        app.listen(8080, () -> System.out.println("☕ Agent demo on http://localhost:8080/support"));
    }
}
