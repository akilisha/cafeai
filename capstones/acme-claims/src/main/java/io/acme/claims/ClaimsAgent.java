package io.acme.claims;

import dev.langchain4j.service.UserMessage;

/**
 * The Acme claims intake agent — a LangChain4j {@code AiService} registered as
 * {@code app.agent("claims", ClaimsAgent.class)}.
 *
 * <p>{@link #intake} drives the tool protocol (lookupClaim / verifyPolicyCoverage
 * / openClaim) over {@link ClaimsApiTools} and returns a typed
 * {@link ClaimsDecision}. {@link #followUp} answers an adjuster's conversational
 * question from the same Redis-backed session. The system prompt is set on the
 * {@code AgentConfig} in {@link ClaimsApp}.
 */
public interface ClaimsAgent {

    ClaimsDecision intake(@UserMessage String request);

    String followUp(@UserMessage String question);
}
