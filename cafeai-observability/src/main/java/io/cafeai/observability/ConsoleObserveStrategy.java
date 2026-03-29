package io.cafeai.observability;

import io.cafeai.core.Attributes;
import io.cafeai.core.ai.PromptRequest;
import io.cafeai.core.ai.PromptResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observation strategy that writes structured, human-readable output
 * to the console for each LLM call.
 *
 * <p>Designed for development and local debugging. In production,
 * use {@link OtelObserveStrategy} which exports to your observability
 * stack.
 */
final class ConsoleObserveStrategy implements ObserveStrategy {

    private static final Logger log = LoggerFactory.getLogger(ConsoleObserveStrategy.class);

    @Override
    public String toString() { return "ConsoleObserveStrategy"; }
}
