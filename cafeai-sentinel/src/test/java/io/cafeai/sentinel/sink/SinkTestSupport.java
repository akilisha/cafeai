package io.cafeai.sentinel.sink;

import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.investigate.CauseCategory;
import io.cafeai.sentinel.investigate.Confidence;
import io.cafeai.sentinel.investigate.Investigation;
import io.cafeai.sentinel.triage.TriageResult;
import io.cafeai.sentinel.triage.Verdict;
import io.cafeai.sentinel.watch.WorkloadRef;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Shared fixtures for the sink tests. */
final class SinkTestSupport {

    private SinkTestSupport() {
    }

    static Incident incident() {
        Incident base = Incident.open("inc-abc12345", "payments",
                new WorkloadRef("Deployment", "web"),
                Instant.parse("2026-09-10T12:00:00Z"),
                new TriageResult(Verdict.ERROR, Set.of("CrashLoopBackOff", "OOMKilled"), "web-1"),
                "web-1", "web-1: app(CrashLoopBackOff last=OOMKilled exit=137 restarts=4)");
        return base.withInvestigation(new Investigation(
                "worker is OOMKilled under load",
                CauseCategory.RESOURCES,
                "limits.memory is 16Mi; RSS reaches ~90Mi",
                Confidence.HIGH,
                List.of("raise limits.memory above 128Mi"),
                List.of("Deployment/web")), Set.of("CrashLoopBackOff", "OOMKilled"));
    }

    static IncidentEvent event(IncidentEvent.Type type) {
        return new IncidentEvent(type, incident());
    }
}
