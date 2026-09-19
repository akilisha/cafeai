package io.cafeai.sentinel.triage;

import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodEvent;
import io.cafeai.sentinel.watch.PodState;
import io.cafeai.sentinel.watch.WorkloadRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TriageRulesTest {

    private final TriageRules triage = new TriageRules();

    private static PodState pod(String phase, List<ContainerState> containers, List<PodEvent> events) {
        return new PodState("demo", "web-abc", "uid-1", new WorkloadRef("Deployment", "web"),
                phase, false, false, containers, events, Instant.now());
    }

    private static ContainerState running() {
        return new ContainerState("app", true, 0, "running", null, null, null, null);
    }

    @Test
    void healthyRunningPodIsBenign() {
        TriageResult r = triage.assess(pod("Running", List.of(running()), List.of()));
        assertThat(r.verdict()).isEqualTo(Verdict.BENIGN);
        assertThat(r.actionable()).isFalse();
    }

    @Test
    void crashLoopBackOffIsError() {
        ContainerState c = new ContainerState("app", false, 5, "waiting",
                "CrashLoopBackOff", "Error", 1, "back-off 40s");
        TriageResult r = triage.assess(pod("Running", List.of(c), List.of()));

        assertThat(r.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(r.reasons()).contains("CrashLoopBackOff", "Error");
    }

    @Test
    void oomKilledFromLastStateIsError() {
        ContainerState c = new ContainerState("worker", false, 3, "waiting",
                "CrashLoopBackOff", "OOMKilled", 137, null);
        TriageResult r = triage.assess(pod("Running", List.of(c), List.of()));

        assertThat(r.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(r.reasons()).contains("OOMKilled", "CrashLoopBackOff");
    }

    @Test
    void imagePullBackOffIsError() {
        ContainerState c = new ContainerState("app", false, 0, "waiting",
                "ImagePullBackOff", null, null, "Back-off pulling image");
        TriageResult r = triage.assess(pod("Pending", List.of(c), List.of()));

        assertThat(r.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(r.reasons()).containsExactly("ImagePullBackOff");
    }

    @Test
    void failedPodPhaseIsError() {
        ContainerState c = new ContainerState("app", false, 0, "terminated",
                "Error", null, 2, null);
        TriageResult r = triage.assess(pod("Failed", List.of(c), List.of()));

        assertThat(r.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(r.reasons()).contains("PodFailed", "Error");
    }

    @Test
    void failedSchedulingEventIsError() {
        PodEvent e = new PodEvent("Warning", "FailedScheduling",
                "0/3 nodes are available: insufficient memory", 4, Instant.now());
        TriageResult r = triage.assess(pod("Pending", List.of(), List.of(e)));

        assertThat(r.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(r.reasons()).containsExactly("FailedScheduling");
    }

    @Test
    void singleUnhealthyEventIsNotYetAnError() {
        PodEvent e = new PodEvent("Warning", "Unhealthy", "readiness probe failed", 1, Instant.now());
        TriageResult r = triage.assess(pod("Running", List.of(running()), List.of(e)));

        assertThat(r.verdict()).isEqualTo(Verdict.BENIGN);
    }

    @Test
    void repeatedUnhealthyEventIsError() {
        PodEvent e = new PodEvent("Warning", "Unhealthy", "readiness probe failed", 5, Instant.now());
        ContainerState c = new ContainerState("app", false, 0, "running", null, null, null, null);
        TriageResult r = triage.assess(pod("Running", List.of(c), List.of(e)));

        assertThat(r.verdict()).isEqualTo(Verdict.ERROR);
        assertThat(r.reasons()).containsExactly("Unhealthy");
    }

    @Test
    void probeFailuresSettingSetsHowManyUnhealthyEventsCountAsAnError() {
        PodEvent e = new PodEvent("Warning", "Unhealthy", "readiness probe failed", 5, Instant.now());
        var pod = pod("Running", List.of(running()), List.of(e));
        io.cafeai.core.config.AppConfig lenient = key ->
            key.name().equals("cafeai.sentinel.probe.failures") ? java.util.Optional.of("6") : java.util.Optional.empty();
        io.cafeai.core.config.AppConfig strict = key ->
            key.name().equals("cafeai.sentinel.probe.failures") ? java.util.Optional.of("2") : java.util.Optional.empty();

        assertThat(new TriageRules(lenient).assess(pod).verdict()).isEqualTo(Verdict.BENIGN);
        assertThat(new TriageRules(strict).assess(pod).verdict()).isEqualTo(Verdict.ERROR);
    }

    @Test
    void preemptionEventIsNotable() {
        PodEvent e = new PodEvent("Warning", "Preempted", "preempted by a higher priority pod", 1, Instant.now());
        TriageResult r = triage.assess(pod("Running", List.of(running()), List.of(e)));

        assertThat(r.verdict()).isEqualTo(Verdict.NOTABLE);
        assertThat(r.reasons()).containsExactly("Preempted");
    }

    @Test
    void gracefulSigtermTerminationIsBenign() {
        ContainerState c = new ContainerState("app", false, 0, "terminated",
                "Completed", null, 143, null);
        TriageResult r = triage.assess(pod("Running", List.of(c), List.of()));

        assertThat(r.verdict()).isEqualTo(Verdict.BENIGN);
    }

    @Test
    void cleanCompletionIsBenign() {
        ContainerState c = new ContainerState("job", false, 0, "terminated",
                "Completed", null, 0, null);
        TriageResult r = triage.assess(pod("Succeeded", List.of(c), List.of()));

        assertThat(r.verdict()).isEqualTo(Verdict.BENIGN);
    }

    @Test
    void errorOutranksNotableOnTheSamePod() {
        ContainerState c = new ContainerState("app", false, 5, "waiting",
                "CrashLoopBackOff", "Error", 1, null);
        PodEvent notable = new PodEvent("Warning", "Preempted", "x", 1, Instant.now());
        TriageResult r = triage.assess(pod("Running", List.of(c), List.of(notable)));

        assertThat(r.verdict()).isEqualTo(Verdict.ERROR);
    }
}
