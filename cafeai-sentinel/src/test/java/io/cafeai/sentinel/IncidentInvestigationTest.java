package io.cafeai.sentinel;

import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.investigate.CauseCategory;
import io.cafeai.sentinel.investigate.Confidence;
import io.cafeai.sentinel.investigate.Investigation;
import io.cafeai.sentinel.triage.TriageRules;
import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodEvent;
import io.cafeai.sentinel.watch.PodState;
import io.cafeai.sentinel.watch.WorkloadRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentInvestigationTest {

    private static final WorkloadRef WEB = new WorkloadRef("Deployment", "web");

    private final List<IncidentEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final FakeInvestigator fake = new FakeInvestigator();
    private final IncidentTracker tracker =
            new IncidentTracker(SentinelConfig.create(), new TriageRules(), Clock.systemUTC())
                    .onIncident(events::add)
                    .investigator(fake)
                    .start();

    @AfterEach
    void tearDown() {
        tracker.close();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static PodState crashing(String podName) {
        ContainerState c = new ContainerState("app", false, 4, "waiting",
                "CrashLoopBackOff", "Error", 1, null);
        return new PodState("demo", podName, "uid-" + podName, WEB, "Running",
                false, false, List.of(c), List.of(), Instant.now());
    }

    private static PodState configError(String podName) {
        ContainerState c = new ContainerState("app", false, 0, "waiting",
                "CreateContainerConfigError", null, null, "configmap \"cfg\" not found");
        PodEvent e = new PodEvent("Warning", "Failed", "configmap not found", 2, Instant.now());
        return new PodState("demo", podName, "uid-" + podName, WEB, "Pending",
                false, false, List.of(c), List.of(e), Instant.now());
    }

    private long countOf(IncidentEvent.Type type) {
        synchronized (events) {
            return events.stream().filter(e -> e.type() == type).count();
        }
    }

    private Incident lastInvestigated() {
        synchronized (events) {
            return events.stream().filter(e -> e.type() == IncidentEvent.Type.INVESTIGATED)
                    .reduce((a, b) -> b).orElseThrow().incident();
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError("condition not met within 3s");
    }

    /** Assert the condition stays true for a short settle window (nothing more happens). */
    private static void assertStays(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 800;
        while (System.currentTimeMillis() < deadline) {
            assertThat(condition.getAsBoolean()).isTrue();
            sleep(50);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void investigationRunsOnOpenAndAttachesResult() {
        tracker.accept(crashing("web-1"));

        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);

        assertThat(fake.calls).hasValue(1);
        Incident incident = lastInvestigated();
        assertThat(incident.investigation()).isEqualTo(fake.result);
        assertThat(incident.investigation().category()).isEqualTo(CauseCategory.RESOURCES);
    }

    @Test
    void aNewErrorReasonReInvestigates() {
        tracker.accept(crashing("web-1"));
        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);

        tracker.accept(configError("web-1"));
        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 2);

        assertThat(fake.calls).hasValue(2);
        assertThat(lastInvestigated().reasons())
                .contains("CrashLoopBackOff", "CreateContainerConfigError", "Failed");
    }

    @Test
    void sameReasonsDoNotReInvestigate() {
        tracker.accept(crashing("web-1"));
        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);

        tracker.accept(crashing("web-2")); // another replica, same reasons

        assertStays(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);
        assertThat(fake.calls).hasValue(1);
    }

    @Test
    void investigationFailureIsIsolated() {
        fake.toThrow = new IllegalStateException("cluster unreachable");
        tracker.accept(crashing("web-1"));

        assertStays(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 0);
        assertThat(tracker.openIncidents()).hasSize(1);
        assertThat(tracker.openIncidents().get(0).investigation()).isNull();

        fake.toThrow = null;
        tracker.accept(configError("web-1")); // a new reason retries
        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);
    }

    // ── fake ─────────────────────────────────────────────────────────────────

    private static final class FakeInvestigator implements Investigator {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException toThrow;
        final Investigation result = new Investigation("memory limit too low",
                CauseCategory.RESOURCES, "limits.memory is 64Mi; the app needs ~200Mi",
                Confidence.HIGH, List.of("raise limits.memory above 256Mi"),
                List.of("Deployment/web"));

        @Override
        public Investigation investigate(Incident incident) {
            calls.incrementAndGet();
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }
}
