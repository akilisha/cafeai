package io.meridian.qualify;

import dev.langchain4j.service.UserMessage;

/**
 * The Meridian pre-qualification agent — a LangChain4j {@code AiService}
 * registered as {@code app.agent("qualify", QualificationAgent.class)}.
 *
 * <p>{@link #assess} drives the forced tool protocol (footprint → DTI → payment)
 * over {@link QualificationTools} and returns a typed {@link QualificationDecision};
 * LangChain4j parses the model output — no hand-rolled JSON. {@link #followUp}
 * answers a loan-officer's conversational question from the same session memory.
 *
 * <p>The system prompt is set on the {@code AgentConfig} in {@link QualifyApp}.
 */
public interface QualificationAgent {

    QualificationDecision assess(@UserMessage String request);

    String followUp(@UserMessage String question);
}
