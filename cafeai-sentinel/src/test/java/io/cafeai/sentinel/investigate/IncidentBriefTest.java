package io.cafeai.sentinel.investigate;

import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.triage.TriageResult;
import io.cafeai.sentinel.triage.Verdict;
import io.cafeai.sentinel.watch.WorkloadRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentBriefTest {

    @Test
    void briefCarriesTheWorkloadReasonsPodsAndEvidence() {
        Instant t0 = Instant.parse("2026-09-09T12:00:00Z");
        TriageResult triage = new TriageResult(Verdict.ERROR,
                Set.of("CrashLoopBackOff", "OOMKilled"), "web-1: CrashLoopBackOff");

        Incident incident = Incident
                .open("inc-abcd1234", "payments", new WorkloadRef("Deployment", "web"),
                        t0, triage, "web-1", "web-1: app(CrashLoopBackOff last=OOMKilled exit=137 restarts=4)")
                .fold(t0.plusSeconds(30), triage, "web-2", "web-2: app(CrashLoopBackOff)", 20);

        String brief = IncidentBrief.of(incident);

        assertThat(brief).contains("inc-abcd1234");
        assertThat(brief).contains("payments");
        assertThat(brief).contains("Deployment/web");
        assertThat(brief).contains("CrashLoopBackOff").contains("OOMKilled");
        assertThat(brief).contains("web-1").contains("web-2");
        assertThat(brief).contains("exit=137");
        assertThat(brief).contains("Signals: 2");
    }
}
