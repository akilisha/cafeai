package io.cafeai.agents.adapter;

import dev.langchain4j.observability.api.event.AiServiceCompletedEvent;
import dev.langchain4j.observability.api.event.AiServiceErrorEvent;
import dev.langchain4j.observability.api.event.AiServiceEvent;
import dev.langchain4j.observability.api.event.AiServiceStartedEvent;
import dev.langchain4j.observability.api.listener.AiServiceListener;
import io.cafeai.core.spi.ObserveBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Whole-invocation observability for an agent, via LangChain4j's
 * {@code AiServiceListener}. Always logs at INFO; when an {@link ObserveBridge}
 * is present it also brackets the invocation with
 * {@link ObserveBridge#beforeAgent(String)} / {@link ObserveBridge#afterAgent}.
 *
 * <p>The bridge context is held in a {@link ThreadLocal}: an {@code AiService}
 * method call and its listener events run synchronously on the caller's thread.
 */
public final class AgentObserveListener {

    private static final Logger log = LoggerFactory.getLogger("io.cafeai.agents");

    private AgentObserveListener() {}

    public static List<AiServiceListener<?>> forAgent(String name, ObserveBridge observeBridge) {
        ThreadLocal<Object> ctx = new ThreadLocal<>();
        return List.of(
            listener(AiServiceStartedEvent.class, e -> {
                log.info("agent '{}' invoked", name);
                if (observeBridge != null) {
                    ctx.set(observeBridge.beforeAgent(name));
                }
            }),
            listener(AiServiceCompletedEvent.class, e -> {
                log.info("agent '{}' completed", name);
                if (observeBridge != null) {
                    observeBridge.afterAgent(ctx.get(), name, null);
                    ctx.remove();
                }
            }),
            listener(AiServiceErrorEvent.class, e -> {
                log.warn("agent '{}' failed: {}", name, String.valueOf(e.error()));
                if (observeBridge != null) {
                    observeBridge.afterAgent(ctx.get(), name, e.error());
                    ctx.remove();
                }
            })
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
