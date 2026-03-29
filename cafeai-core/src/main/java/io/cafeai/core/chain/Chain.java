package io.cafeai.core.chain;

import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named, composable, middleware-invocable AI processing pipeline.
 *
 * <p>A chain is a named sequence of {@link ChainStep}s that processes
 * a request through a defined pipeline. Chains implement {@link Middleware}
 * and can be used anywhere middleware is accepted. Chains can include
 * other chains as steps via {@link Steps#chain(String)}.
 *
 * <p>Register named chains at startup; invoke them from handlers:
 *
 * <pre>{@code
 *   app.chain("triage",
 *       Steps.guard(GuardRail.pii()),
 *       Steps.prompt("classify"),
 *       Steps.branch(
 *           req -> "billing".equals(req.attribute("classification")),
 *           Steps.chain("billing-handler"),
 *           Steps.chain("general-handler")
 *       ));
 *
 *   app.post("/support", (req, res, next) ->
 *       app.chain("triage").run(req, res, next));
 * }</pre>
 */
public final class Chain implements Middleware {

    private final String          name;
    private final List<ChainStep> steps;

    /**
     * Public -- constructed by {@code CafeAIApp.chain()} and optionally
     * by application code building chains programmatically.
     */
    public Chain(String name, List<ChainStep> steps) {
        this.name  = name;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /** The name this chain was registered under. */
    public String name() { return name; }

    /** The steps in this chain, in execution order. */
    public List<ChainStep> steps() { return steps; }

    /**
     * Executes this chain as a middleware.
     *
     * <p>Steps execute in order via a right-fold over the step list --
     * each step receives a {@code Next} that points to the remaining steps.
     * If any step short-circuits (sends a response without calling
     * {@code next.run()}), the remaining steps are skipped, identical to
     * how the main middleware pipeline behaves.
     */
    @Override
    public void handle(Request req, Response res, Next next) {
        if (steps.isEmpty()) { next.run(); return; }

        // Right-fold: build the Next chain from the end backwards so
        // each step naturally calls the next one via next.run()
        Next chain = next;
        for (int i = steps.size() - 1; i >= 0; i--) {
            final ChainStep step       = steps.get(i);
            final Next      downstream = chain;
            chain = () -> {
                try {
                    step.handle(req, res, downstream);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            };
        }
        chain.run();
    }

    /**
     * Convenience method -- invokes the chain directly from a handler body.
     *
     * <pre>{@code
     *   app.post("/chat", (req, res, next) ->
     *       app.chain("triage").run(req, res, next));
     * }</pre>
     */
    public void run(Request req, Response res, Next next) {
        handle(req, res, next);
    }

    /**
     * Returns a new chain with the given step appended -- chains are immutable.
     */
    public Chain use(ChainStep step) {
        List<ChainStep> newSteps = new ArrayList<>(steps);
        newSteps.add(step);
        return new Chain(name, newSteps);
    }

    /** Adds a middleware directly as a step. */
    public Chain use(Middleware middleware) {
        return use((ChainStep) middleware);
    }

    @Override
    public String toString() {
        return "Chain[" + name + ", " + steps.size() + " steps]";
    }
}
