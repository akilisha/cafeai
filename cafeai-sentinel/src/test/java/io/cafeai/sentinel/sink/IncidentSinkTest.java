package io.cafeai.sentinel.sink;

import io.cafeai.sentinel.incident.IncidentEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentSinkTest {

    @Test
    void ofFansOutToEverySinkInOrder() {
        List<String> order = new ArrayList<>();
        IncidentSink a = e -> order.add("a");
        IncidentSink b = e -> order.add("b");

        IncidentSink.of(a, b).publish(SinkTestSupport.event(IncidentEvent.Type.OPENED));

        assertThat(order).containsExactly("a", "b");
    }

    @Test
    void aThrowingSinkDoesNotStopTheOthers() {
        List<String> reached = new ArrayList<>();
        IncidentSink boom = e -> {
            throw new IllegalStateException("sink down");
        };
        IncidentSink ok = e -> reached.add(e.incident().id());

        IncidentSink.of(boom, ok).publish(SinkTestSupport.event(IncidentEvent.Type.RESOLVED));

        assertThat(reached).containsExactly("inc-abc12345");
    }

    @Test
    void isUsableAsAConsumer() {
        List<IncidentEvent> seen = new ArrayList<>();
        IncidentSink sink = seen::add;
        sink.accept(SinkTestSupport.event(IncidentEvent.Type.UPDATED));
        assertThat(seen).hasSize(1);
    }
}
