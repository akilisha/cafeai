package io.cafeai.sentinel;

import io.cafeai.core.ai.TokenBudget;
import io.cafeai.sentinel.incident.Incident;
import io.cafeai.sentinel.incident.IncidentEvent;
import io.cafeai.sentinel.investigate.CauseCategory;
import io.cafeai.sentinel.investigate.Confidence;
import io.cafeai.sentinel.investigate.Investigation;
import io.cafeai.sentinel.triage.TriageRules;
import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodState;
import io.cafeai.sentinel.watch.WorkloadRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 4 — token-budget gating and redaction of investigation results. */
class IncidentTrackerPhase4Test {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-09T12:00:00Z"));
    private final List<IncidentEvent> events = Collections.synchronizedList(new ArrayList<>());
    private final FakeInvestigator fake = new FakeInvestigator();

    private IncidentTracker tracker;

    @AfterEach
    void tearDown() {
        if (tracker != null) {
            tracker.close();
        }
    }

    private IncidentTracker start(SentinelConfig config) {
        tracker = new IncidentTracker(config, new TriageRules(), clock)
                .onIncident(events::add)
                .investigator(fake)
                .start();
        return tracker;
    }

    private static PodState crashing(String workload, String podName) {
        ContainerState c = new ContainerState("app", false, 3, "waiting",
                "CrashLoopBackOff", "Error", 1, null);
        return new PodState("demo", podName, "uid-" + podName,
                new WorkloadRef("Deployment", workload), "Running",
                false, false, List.of(c), List.of(), Instant.now());
    }

    private long countOf(IncidentEvent.Type type) {
        synchronized (events) {
            return events.stream().filter(e -> e.type() == type).count();
        }
    }

    private Incident incidentFor(String workload) {
        return tracker.openIncidents().stream()
                .filter(i -> i.workload().name().equals(workload))
                .findFirst().orElse(null);
    }

    @Test
    void tokenBudgetDefersASecondInvestigationUntilTheWindowResets() {
        // budget of exactly one investigation's worth per minute
        start(SentinelConfig.create()
                .tokenBudget(TokenBudget.perMinute(IncidentTracker.ESTIMATED_TOKENS_PER_INVESTIGATION)));

        tracker.accept(crashing("web", "web-1"));
        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);

        tracker.accept(crashing("api", "api-1")); // second workload — budget is spent
        assertStays(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);
        assertThat(incidentFor("api").investigation()).isNull();

        clock.advance(Duration.ofSeconds(61)); // window resets
        tracker.sweep();                        // sweep retries the deferred one
        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 2);
        assertThat(incidentFor("api").investigation()).isNotNull();
    }

    @Test
    void investigationResultIsRedactedBeforeItReachesTheIncident() {
        fake.result = new Investigation(
                "startup log leaked DB_PASSWORD=hunter2",
                CauseCategory.CONFIG,
                "bad DSN postgres://u:p@db:5432/app",
                Confidence.MEDIUM,
                List.of("rotate the exposed credential"),
                List.of("Secret/db-creds"));

        start(SentinelConfig.create()); // redact on by default

        tracker.accept(crashing("web", "web-1"));
        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);

        Investigation folded = incidentFor("web").investigation();
        assertThat(folded.summary()).doesNotContain("hunter2");
        assertThat(folded.likelyCause()).contains("[REDACTED]@db");
    }

    @Test
    void redactionCanBeTurnedOff() {
        fake.result = new Investigation("saw DB_PASSWORD=hunter2", CauseCategory.CONFIG,
                "x", Confidence.LOW, List.of(), List.of());

        start(SentinelConfig.create().redact(false));

        tracker.accept(crashing("web", "web-1"));
        awaitTrue(() -> countOf(IncidentEvent.Type.INVESTIGATED) == 1);

        assertThat(incidentFor("web").investigation().summary()).contains("hunter2");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void awaitTrue(BooleanSupplier c) {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            if (c.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        throw new AssertionError("condition not met within 3s");
    }

    private static void assertStays(BooleanSupplier c) {
        long deadline = System.currentTimeMillis() + 700;
        while (System.currentTimeMillis() < deadline) {
            assertThat(c.getAsBoolean()).isTrue();
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

    private static final class FakeInvestigator implements Investigator {
        volatile Investigation result = new Investigation("ok", CauseCategory.APPLICATION,
                "cause", Confidence.MEDIUM, List.of("do a thing"), List.of("Deployment/web"));

        @Override
        public Investigation investigate(Incident incident) {
            return result;
        }
    }

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
