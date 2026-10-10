package io.cafeai.core.audit;

import io.cafeai.core.ai.UsageReport;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.internal.CurrentRequest;
import io.cafeai.core.routing.Request;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One auditable thing that happened: a model call, a tool call, or a guardrail flagging
 * something. Each names who it was for, so an organisation can answer "who used which model, at
 * what cost, what did tools do for them, and what was blocked".
 *
 * <p><b>Metadata only.</b> No prompt, answer or document text is ever included, no tool
 * arguments or results, and no guardrail reason (a reason can quote what was flagged). Audit records name people, so they
 * are personal data; what is kept, and for how long, is the sink's decision.
 *
 * <p>Received by an {@link AuditSink} registered with {@code app.audit(sink)}.
 */
public sealed interface AuditEvent permits AuditEvent.ModelCall, AuditEvent.ToolCall, AuditEvent.GuardrailFlag {

    /** When it happened. */
    Instant at();

    /**
     * Who it was for: the issuer and subject of the request's verified identity, or
     * {@code null} for an anonymous request or work with no request behind it.
     */
    Identity.Key caller();

    /**
     * The route of the request ({@code "GET /orders/:id"}), or
     * {@link io.cafeai.core.ai.UsageReport#NO_REQUEST} outside any request.
     */
    String route();

    /**
     * One call to a model.
     *
     * @param model        the model id
     * @param inputTokens  tokens sent, as the provider reported them
     * @param outputTokens tokens generated
     * @param cost         dollars, or {@code null} when the model has no price ({@code app.pricing})
     */
    record ModelCall(Instant at, Identity.Key caller, String route, String model,
                     long inputTokens, long outputTokens, Double cost) implements AuditEvent {
        public ModelCall {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(model, "model");
        }
    }

    /**
     * A tool ran: an MCP client called one of the app's tools, or one of the app's agents called
     * one. Its arguments and result are not recorded; they can hold anything.
     *
     * @param tool     the tool's name
     * @param via      who called it
     * @param failed   whether it ended in an error
     * @param duration how long it ran
     */
    record ToolCall(Instant at, Identity.Key caller, String route, String tool, Via via,
                    boolean failed, Duration duration) implements AuditEvent {
        public ToolCall {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(via, "via");
            Objects.requireNonNull(duration, "duration");
        }

        /**
         * A tool call that just ended, for the request this thread is working for (or for no
         * request): the caller is its verified identity, the route its matched pattern.
         */
        public static ToolCall now(String tool, Via via, boolean failed, Duration duration) {
            Request request = CurrentRequest.get().orElse(null);
            if (request == null) {
                return new ToolCall(Instant.now(), null, UsageReport.NO_REQUEST, tool, via, failed, duration);
            }
            Object pattern = request.attribute("_routePattern");
            return new ToolCall(Instant.now(), request.identity().map(Identity::key).orElse(null),
                    request.method() + " " + (pattern != null ? pattern : "(unmatched)"),
                    tool, via, failed, duration);
        }

        /** Who called a tool. */
        public enum Via {
            /** An MCP client: an agent outside the app, with its own identity or a person's. */
            MCP,
            /** One of the app's own agents, while answering a request. */
            AGENT
        }
    }

    /**
     * A guardrail flagged something. {@code action} says what followed: {@code BLOCK} stopped
     * it, {@code WARN} and {@code LOG} let it through.
     *
     * @param guardrail the guardrail's name
     * @param stage     what it was screening
     * @param action    what the guardrail did about it
     */
    record GuardrailFlag(Instant at, Identity.Key caller, String route, String guardrail,
                         Stage stage, GuardRail.Action action) implements AuditEvent {
        public GuardrailFlag {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(guardrail, "guardrail");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(action, "action");
        }

        /**
         * A flag raised now, for {@code request} (or for no request, when {@code null}): the
         * caller is the request's verified identity, the route its matched pattern. A guardrail
         * in a filter runs before routing, so its route is {@code "<METHOD> (unmatched)"}.
         */
        public static GuardrailFlag forRequest(Request request, String guardrail, Stage stage,
                                               GuardRail.Action action) {
            if (request == null) {
                return new GuardrailFlag(Instant.now(), null, UsageReport.NO_REQUEST, guardrail, stage, action);
            }
            Object pattern = request.attribute("_routePattern");
            return new GuardrailFlag(Instant.now(),
                    request.identity().map(Identity::key).orElse(null),
                    request.method() + " " + (pattern != null ? pattern : "(unmatched)"),
                    guardrail, stage, action);
        }
    }

    /** What a guardrail was screening when it flagged. */
    enum Stage {
        /** The caller's input, before the model saw it. */
        REQUEST,
        /** The model's answer, before the caller saw it. */
        RESPONSE,
        /** A document retrieved for RAG, before it entered the model's context. */
        RETRIEVED
    }
}
