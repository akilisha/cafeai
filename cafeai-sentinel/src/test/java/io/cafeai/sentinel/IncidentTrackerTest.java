package io.cafeai.sentinel;

import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.incident.IncidentStatus;
import io.cafeai.sentinel.triage.TriageRules;
import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodEvent;
import io.cafeai.sentinel.watch.PodState;
import io.cafeai.sentinel.watch.WorkloadRef;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentTrackerTest {

    private static final WorkloadRef WEB = new WorkloadRef("Deployment", "web");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-09T12:00:00Z"));
    private final List<IncidentEvent> events = new ArrayList<>();
    private final IncidentTracker tracker =
            new IncidentTracker(SentinelConfig.create().updateDebounce(Duration.ZERO),
                    new TriageRules(), clock)
                    .onIncident(events::add);

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static PodState crashing(String podName, boolean preExisting) {
        ContainerState c = new ContainerState("app", false, 4, "waiting",
                "CrashLoopBackOff", "Error", 1, null);
        return new PodState("demo", podName, "uid-" + podName, WEB, "Running",
                false, preExisting, List.of(c), List.of(), Instant.now());
    }

    private static PodState healthy(String podName) {
        ContainerState c = new ContainerState("app", true, 4, "running", null, null, null, null);
        return new PodState("demo", podName, "uid-" + podName, WEB, "Running",
                false, false, List.of(c), List.of(), Instant.now());
    }

    private static PodState deleted(String podName) {
        return new PodState("demo", podName, "uid-" + podName, WEB, "Running",
                true, false, List.of(), List.of(), Instant.now());
    }

    private IncidentEvent last() {
        return events.get(events.size() - 1);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void twoCrashingReplicasProduceOneIncident() {
        tracker.accept(crashing("web-1", false));
        tracker.accept(crashing("web-2", false));

        assertThat(events).extracting(IncidentEvent::type)
                .containsExactly(IncidentEvent.Type.OPENED, IncidentEvent.Type.UPDATED);
        assertThat(tracker.openIncidents()).hasSize(1);

        Incident incident = last().incident();
        assertThat(incident.affectedPods()).containsExactlyInAnyOrder("web-1", "web-2");
        assertThat(incident.signalCount()).isEqualTo(2);
        assertThat(incident.reasons()).contains("CrashLoopBackOff", "Error");
    }

    @Test
    void preExistingFailureIsNotTrackedByDefault() {
        tracker.accept(crashing("web-1", true));

        assertThat(events).isEmpty();
        assertThat(tracker.openIncidents()).isEmpty();
    }

    @Test
    void preExistingFailureIsTrackedWhenOptedIn() {
        var optedIn = new IncidentTracker(
                SentinelConfig.create().investigateOnStartup(true), new TriageRules(), clock)
                .onIncident(events::add);

        optedIn.accept(crashing("web-1", true));

        assertThat(events).extracting(IncidentEvent::type).containsExactly(IncidentEvent.Type.OPENED);
    }

    @Test
    void recoveredAndDeletedPodsDrainTheAffectedSet() {
        tracker.accept(crashing("web-1", false));
        tracker.accept(crashing("web-2", false));

        tracker.accept(healthy("web-1"));
        tracker.accept(deleted("web-2"));

        assertThat(tracker.openIncidents()).hasSize(1);
        assertThat(tracker.openIncidents().get(0).affectedPods()).isEmpty();
    }

    @Test
    void incidentResolvesOnlyAfterTheCooldown() {
        tracker.accept(crashing("web-1", false));
        tracker.accept(healthy("web-1"));

        clock.advance(Duration.ofSeconds(90));
        tracker.sweep();
        assertThat(last().type()).isEqualTo(IncidentEvent.Type.OPENED); // not resolved yet
        assertThat(tracker.openIncidents()).hasSize(1);

        clock.advance(Duration.ofSeconds(60)); // now > 2 min since the error
        tracker.sweep();

        assertThat(last().type()).isEqualTo(IncidentEvent.Type.RESOLVED);
        assertThat(last().incident().status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(tracker.openIncidents()).isEmpty();
    }

    @Test
    void aNewErrorReasonIsFoldedIn() {
        tracker.accept(crashing("web-1", false));

        ContainerState c = new ContainerState("app", false, 0, "waiting",
                "CreateContainerConfigError", null, null, "configmap \"cfg\" not found");
        PodEvent e = new PodEvent("Warning", "Failed", "Error: configmap not found", 2, Instant.now());
        tracker.accept(new PodState("demo", "web-1", "uid-web-1", WEB, "Pending",
                false, false, List.of(c), List.of(e), Instant.now()));

        Incident incident = last().incident();
        assertThat(incident.reasons()).contains("CrashLoopBackOff", "CreateContainerConfigError", "Failed");
    }

    @Test
    void benignPodForUnknownWorkloadDoesNothing() {
        tracker.accept(healthy("web-1"));
        assertThat(events).isEmpty();
        assertThat(tracker.openIncidents()).isEmpty();
    }

    @Test
    void updatedEventsAreDebounced() {
        var seen = new ArrayList<IncidentEvent>();
        var debounced = new IncidentTracker(
                SentinelConfig.create().updateDebounce(Duration.ofSeconds(5)), new TriageRules(), clock)
                .onIncident(seen::add);

        debounced.accept(crashing("web-1", false)); // OPENED — immediate
        debounced.accept(crashing("web-2", false)); // UPDATED within window — deferred
        debounced.accept(crashing("web-1", false)); // still deferred

        assertThat(seen).extracting(IncidentEvent::type).containsExactly(IncidentEvent.Type.OPENED);

        clock.advance(Duration.ofSeconds(6));
        debounced.sweep(); // flushes the pending update

        assertThat(seen).extracting(IncidentEvent::type)
                .containsExactly(IncidentEvent.Type.OPENED, IncidentEvent.Type.UPDATED);
        assertThat(seen.get(1).incident().affectedPods()).containsExactlyInAnyOrder("web-1", "web-2");
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public Instant instant() {
            return now;
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
