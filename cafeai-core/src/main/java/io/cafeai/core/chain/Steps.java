package io.cafeai.core.chain;

import io.cafeai.core.CafeAI;
import io.cafeai.core.Attributes;
import io.cafeai.core.middleware.Middleware;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Factory methods for the built-in {@link ChainStep}s.
 *
 * <p>Use these to build expressive, readable AI processing pipelines:
 *
 * <pre>{@code
 *   app.chain("support-triage",
 *       Steps.guard(GuardRail.pii()),
 *       Steps.prompt("classify"),
 *       Steps.branch(
 *           req -> "billing".equals(req.attribute("classification")),
 *           Steps.chain("billing-chain"),
 *           Steps.chain("general-chain")
 *       ));
 * }</pre>
 */
public final class Steps {

    private Steps() {}

    // ── Prompt ────────────────────────────────────────────────────────────────

    /**
     * Runs a named prompt template against the registered AI provider.
     *
     * <p>The template is rendered with {@code req.body()} as the variable map.
     * The response text is stored in {@code req.attribute(Attributes.PROMPT_RESPONSE)}.
     *
     * <pre>{@code
     *   Steps.prompt("classify")       // renders the "classify" template
     *   Steps.prompt("summarise")      // renders the "summarise" template
     * }</pre>
     *
     * @param templateName the name of a template registered via {@code app.template()}
     */
    public static ChainStep prompt(String templateName) {
        return (req, res, next) -> {
            CafeAI app = req.app();
            // Render the template with request body as variable map
            @SuppressWarnings("unchecked")
            Map<String, Object> vars = (Map<String, Object>) req.body();
            String rendered = app.template(templateName)
                                 .render(vars != null ? vars : Map.of());

            // Execute the prompt and store the response for downstream steps
            var response = app.prompt(rendered)
                              .session(req.header("X-Session-Id"))
                              .call();

            req.setAttribute(Attributes.PROMPT_RESPONSE, response);
            req.setAttribute(Attributes.LAST_RESPONSE_TEXT, response.text());
            next.run();
        };
    }

    /**
     * Runs an inline prompt (not a named template).
     *
     * <pre>{@code
     *   Steps.prompt(req -> "Summarise this in one sentence: " + req.bodyText())
     * }</pre>
     */
    public static ChainStep prompt(Function<io.cafeai.core.routing.Request, String> promptBuilder) {
        return (req, res, next) -> {
            String message  = promptBuilder.apply(req);
            var response    = req.app().prompt(message)
                                       .session(req.header("X-Session-Id"))
                                       .call();
            req.setAttribute(Attributes.PROMPT_RESPONSE, response);
            req.setAttribute(Attributes.LAST_RESPONSE_TEXT, response.text());
            next.run();
        };
    }

    // ── Guard ─────────────────────────────────────────────────────────────────

    /**
     * Applies one or more guardrails as a chain step.
     *
     * <p>Guardrails run in the order provided. If any guardrail blocks
     * the request (sends a response without calling {@code next.run()}),
     * the remaining guardrails and downstream steps are skipped.
     *
     * <pre>{@code
     *   Steps.guard(GuardRail.pii())
     *   Steps.guard(GuardRail.pii(), GuardRail.jailbreak())
     * }</pre>
     */
    public static ChainStep guard(io.cafeai.core.guardrails.GuardRail... guardrails) {
        if (guardrails.length == 0) return (req, res, next) -> next.run();
        Middleware composed = guardrails[0];
        for (int i = 1; i < guardrails.length; i++) {
            composed = composed.then(guardrails[i]);
        }
        final Middleware pipeline = composed;
        return (req, res, next) -> pipeline.handle(req, res, next);
    }

    // ── Branch ────────────────────────────────────────────────────────────────

    /**
     * Conditional routing — executes one of two steps based on a predicate.
     *
     * <pre>{@code
     *   Steps.branch(
     *       req -> "billing".equals(req.attribute("classification")),
     *       Steps.chain("billing-handler"),    // true branch
     *       Steps.chain("general-handler")     // false branch
     *   )
     * }</pre>
     *
     * @param predicate evaluates the request to choose a branch
     * @param trueBranch  step to execute when predicate returns {@code true}
     * @param falseBranch step to execute when predicate returns {@code false}
     */
    public static ChainStep branch(
            Predicate<io.cafeai.core.routing.Request> predicate,
            ChainStep trueBranch,
            ChainStep falseBranch) {
        return (req, res, next) -> {
            ChainStep chosen = predicate.test(req) ? trueBranch : falseBranch;
            chosen.handle(req, res, next);
        };
    }

    /**
     * One-sided branch — executes a step only when the predicate matches,
     * otherwise calls {@code next.run()} directly.
     *
     * <pre>{@code
     *   Steps.when(
     *       req -> req.header("X-Premium") != null,
     *       Steps.prompt("premium-response")
     *   )
     * }</pre>
     */
    public static ChainStep when(
            Predicate<io.cafeai.core.routing.Request> predicate,
            ChainStep step) {
        return (req, res, next) -> {
            if (predicate.test(req)) {
                step.handle(req, res, next);
            } else {
                next.run();
            }
        };
    }

    // ── Chain reference ───────────────────────────────────────────────────────

    /**
     * References a named chain as a step in another chain.
     *
     * <p>The referenced chain is looked up at execution time — not at
     * registration time. This allows forward references (a chain can
     * reference itself or chains registered after it).
     *
     * <pre>{@code
     *   app.chain("outer",
     *       Steps.prompt("classify"),
     *       Steps.chain("inner"));   // inner registered later — fine
     *
     *   app.chain("inner",
     *       Steps.prompt("handle"));
     * }</pre>
     *
     * @param chainName the name of a chain registered via {@code app.chain()}
     */
    public static ChainStep chain(String chainName) {
        return (req, res, next) -> {
            Chain target = req.app().chain(chainName);
            if (target == null) {
                throw new IllegalStateException(
                    "Chain not found: '" + chainName + "'. " +
                    "Register it first with app.chain(\"" + chainName + "\", ...).");
            }
            target.handle(req, res, next);
        };
    }

    // ── Transform ─────────────────────────────────────────────────────────────

    /**
     * Applies a transformation to the last prompt response text and stores
     * the result back as an attribute.
     *
     * <p>Useful for post-processing LLM output before the next step sees it:
     *
     * <pre>{@code
     *   Steps.transform(text -> text.trim().toLowerCase())
     * }</pre>
     */
    public static ChainStep transform(Function<String, String> transformer) {
        return (req, res, next) -> {
            String last = (String) req.attribute(Attributes.LAST_RESPONSE_TEXT);
            if (last != null) {
                req.setAttribute(Attributes.LAST_RESPONSE_TEXT, transformer.apply(last));
            }
            next.run();
        };
    }

    // ── RAG ───────────────────────────────────────────────────────────────────

    /**
     * Runs the RAG retrieval step explicitly within a chain.
     *
     * <p>Normally RAG runs automatically inside {@code app.prompt().call()}.
     * This step is for pipelines where you want to retrieve context in one
     * step and use it explicitly in the next:
     *
     * <pre>{@code
     *   app.chain("rag-pipeline",
     *       Steps.rag(),                    // retrieve context into req attributes
     *       Steps.prompt("answer-with-rag") // prompt that uses the stored context
     *   );
     * }</pre>
     */
    public static ChainStep rag() {
        return (req, res, next) -> {
            // RAG retrieval is automatically wired into app.prompt().call().
            // This step is a semantic marker — it makes the intent explicit in
            // the chain definition. Full explicit retrieval API in Phase 8.
            next.run();
        };
    }
}
