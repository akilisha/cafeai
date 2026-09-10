package io.cafeai.sentinel.sink;

import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.investigate.Investigation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Writes each incident lifecycle event to SLF4J — {@code OPENED} / {@code INVESTIGATED}
 * at WARN, the rest at INFO. The default sink and the one you always want on.
 */
public final class LogSink implements IncidentSink {

    private final Logger log;

    public LogSink() {
        this(LoggerFactory.getLogger(LogSink.class));
    }

    public LogSink(Logger log) {
        this.log = log;
    }

    @Override
    public void publish(IncidentEvent event) {
        Incident i = event.incident();
        String reasons = String.join(", ", i.reasons());
        String pods = String.join(", ", i.affectedPods());

        switch (event.type()) {
            case OPENED -> log.warn("● OPENED   {} [{}] {} — {} (pods: {})",
                    i.id(), i.severity(), i.workload(), reasons, pods);
            case UPDATED -> log.info("● updated  {} [{}] {} — {} signals; reasons: {}; pods: {}",
                    i.id(), i.severity(), i.workload(), i.signalCount(), reasons, pods);
            case INVESTIGATED -> logInvestigated(i);
            case RESOLVED -> log.info("○ RESOLVED {} {} — was [{}], {} signals over {}",
                    i.id(), i.workload(), i.severity(), i.signalCount(),
                    Duration.between(i.firstSeen(), i.lastSeen()));
        }
        if ((event.type() == IncidentEvent.Type.OPENED || event.type() == IncidentEvent.Type.UPDATED)
                && !i.evidence().isEmpty()) {
            log.info("             {}", i.evidence().get(i.evidence().size() - 1));
        }
    }

    private void logInvestigated(Incident i) {
        Investigation inv = i.investigation();
        if (inv == null) {
            return;
        }
        log.warn("✔ INVESTIGATED {} {} — [{}/{}] {}",
                i.id(), i.workload(), inv.category(), inv.confidence(), inv.summary());
        log.info("             cause: {}", inv.likelyCause());
        for (String action : inv.suggestedActions()) {
            log.info("             → {}", action);
        }
        if (!inv.relatedObjects().isEmpty()) {
            log.info("             objects: {}", String.join(", ", inv.relatedObjects()));
        }
    }
}
