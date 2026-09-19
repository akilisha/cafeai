package io.cafeai.sentinel;

import io.cafeai.core.config.AppConfig;
import io.cafeai.sentinel.investigate.KubeTools;
import io.cafeai.sentinel.triage.TriageRules;
import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodState;
import io.cafeai.sentinel.watch.WorkloadRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The sentinel's tunable values: defaults pinned, and the ones with a seam shown to take effect. */
@DisplayName("sentinel settings")
class SentinelSettingsTest {

    private static AppConfig config(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key.name()));
    }

    @Test @DisplayName("every default is the value the constant had")
    void defaults() {
        assertThat(IncidentTracker.EVIDENCE_MAX.defaultValue()).isEqualTo(20);
        assertThat(IncidentTracker.INVESTIGATION_WORKERS.defaultValue()).isEqualTo(2);
        assertThat(IncidentTracker.SWEEP_INTERVAL.defaultValue()).isEqualTo(Duration.ofSeconds(30));
        assertThat(IncidentTracker.INVESTIGATION_TOKENS.defaultValue()).isEqualTo(20_000L);
        assertThat(IncidentTracker.INVESTIGATION_FAILURES.defaultValue()).isEqualTo(3);
        assertThat(ClusterWatch.EVENTS_PER_POD.defaultValue()).isEqualTo(12);
        assertThat(ClusterWatch.SYNC_TIMEOUT.defaultValue()).isEqualTo(Duration.ofSeconds(30));
        assertThat(KubeTools.LOG_LINES.defaultValue()).isEqualTo(200);
        assertThat(KubeTools.EVENTS.defaultValue()).isEqualTo(40);
        assertThat(KubeTools.REPLICASETS.defaultValue()).isEqualTo(8);
        assertThat(TriageRules.PROBE_FAILURES.defaultValue()).isEqualTo(3);
        assertThat(SentinelConfig.RESOLVE_AFTER.defaultValue()).isEqualTo(Duration.ofMinutes(2));
        assertThat(SentinelConfig.UPDATE_DEBOUNCE.defaultValue()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test @DisplayName("SentinelConfig starts from the settings, and its own setters win")
    void sentinelConfig() {
        var config = SentinelConfig.create();

        assertThat(config.resolveAfter()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.updateDebounce()).isEqualTo(Duration.ofSeconds(3));
        assertThat(config.resolveAfter(Duration.ofSeconds(10)).resolveAfter()).isEqualTo(Duration.ofSeconds(10));
    }

    // -- evidence cap ---------------------------------------------------------------------------------

    private static PodState crashing(String podName) {
        var workload = new WorkloadRef("Deployment", "web");
        var container = new ContainerState("app", false, 4, "waiting", "CrashLoopBackOff", "Error", 1, null);
        return new PodState("demo", podName, "uid-" + podName, workload, "Running",
            false, false, List.of(container), List.of(), Instant.now());
    }

    private static int evidenceAfterFiveCrashes(Map<String, String> settings) {
        var clock = Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC);
        try (var tracker = new IncidentTracker(SentinelConfig.create().updateDebounce(Duration.ZERO),
                new TriageRules(), clock, config(settings))) {
            for (int i = 1; i <= 5; i++) {
                tracker.accept(crashing("web-" + i));
            }
            return tracker.openIncidents().get(0).evidence().size();
        }
    }

    @Test @DisplayName("cafeai.sentinel.evidence.max caps the evidence kept on an incident")
    void evidenceCap() {
        int uncapped = evidenceAfterFiveCrashes(Map.of());
        int capped = evidenceAfterFiveCrashes(Map.of("cafeai.sentinel.evidence.max", "2"));

        assertThat(uncapped).isGreaterThan(2);
        assertThat(capped).isEqualTo(2);
    }

    @Test @DisplayName("a value the tracker cannot use is refused, naming the setting")
    void invalid() {
        assertThatThrownBy(() -> new IncidentTracker(SentinelConfig.create(), new TriageRules(),
                Clock.systemUTC(), config(Map.of("cafeai.sentinel.investigation.workers", "0"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cafeai.sentinel.investigation.workers");
    }
}
