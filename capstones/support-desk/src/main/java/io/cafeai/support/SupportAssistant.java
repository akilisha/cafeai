package io.cafeai.support;

import dev.langchain4j.service.UserMessage;

/**
 * The Helios support agent — a LangChain4j {@code AiService} registered as
 * {@code app.agent("support", SupportAssistant.class)}.
 *
 * <p>CafeAI wires it with the app-level RAG retriever (Helios docs) and session
 * memory, plus {@link GitHubTools} for live issue lookups. The system prompt is
 * set on the {@code AgentConfig}, not here, so it stays next to the wiring.
 */
public interface SupportAssistant {

    String answer(@UserMessage String question);
}
