package io.cafeai.agents.adapter;

import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Whole-invocation observability for an agent, via LangChain4j's
 * {@code AiServiceListener}. For v1 this logs at INFO; routing the events through
 * CafeAI's {@code ObserveBridge} (spans, token counts) is a follow-on that needs
 * {@code beforeAgent}/{@code afterAgent} on that SPI.
 */
public final class AgentObserveListener {

    private static final Logger log = LoggerFactory.getLogger("io.cafeai.agents");

    private AgentObserveListener() {}

    public static List<AiServiceListener<?>> forAgent(String name) {
        return List.of(
            listener(AiServiceStartedEvent.class,
                e -> log.info("agent '{}' invoked", name)),
            listener(AiServiceCompletedEvent.class,
                e -> log.info("agent '{}' completed", name)),
            listener(AiServiceErrorEvent.class,
                e -> log.warn("agent '{}' failed: {}", name, String.valueOf(e.error())))
        );
    }

    private static <T extends AiServiceEvent> AiServiceListener<T> listener(
            Class<T> eventClass, Consumer<T> onEvent) {
        return new AiServiceListener<>() {
            @Override public Class<T> getEventClass() { return eventClass; }
            @Override public void onEvent(T event) { onEvent.accept(event); }
        };
    }
}
