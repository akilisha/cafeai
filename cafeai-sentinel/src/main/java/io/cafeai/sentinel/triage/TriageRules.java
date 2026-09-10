package io.cafeai.sentinel.triage;

import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodEvent;
import io.cafeai.sentinel.watch.PodState;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rules-only triage — classify a correlated {@link PodState} from its container
 * states, pod phase and recent pod Events. No model call: the Kubernetes
 * {@code reason} fields carry most of the signal, and a lookup table gets the
 * common failures right. An LLM classifier for the ambiguous remainder is a
 * later fallback (ROADMAP-18 Phase 3+), not a dependency here.
 *
 * <p>Precedence is container state → pod phase → Events. Container state comes
 * first because it holds {@code OOMKilled} / exit codes that never surface as
 * Events. The verdict is the most severe signal found; every matched reason is
 * returned so the incident accumulates the full picture.
 *
 * <p><strong>Not detected in Phase 2:</strong> scale-to-zero / endpoint loss — a
 * graceful scale-down terminates pods with SIGTERM (exit 143) and emits no
 * failure signal, by design. Surfacing "service has no endpoints" needs an
 * Endpoints watch, which lands with a later phase.
 */
public final class TriageRules {

    /** Waiting-state reasons that mean the container is stuck failing. */
    static final Set<String> ERROR_WAITING = Set.of(
            "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull", "ImageInspectError",
            "InvalidImageName", "ErrImageNeverPull", "RegistryUnavailable",
            "CreateContainerConfigError", "CreateContainerError", "RunContainerError",
            "PreStartHookError", "PostStartHookError", "CNINetworkError");

    /** Previous-termination reasons that mean the last run failed hard. */
    static final Set<String> ERROR_TERMINATION = Set.of(
            "OOMKilled", "Error", "ContainerCannotRun", "DeadlineExceeded", "StartError");

    /** Warning-event reasons that mean a failure. */
    static final Set<String> ERROR_EVENTS = Set.of(
            "BackOff", "Failed", "FailedScheduling", "FailedMount", "FailedAttachVolume",
            "FailedCreatePodSandBox", "FailedCreatePodContainer", "FailedSync",
            "OOMKilling", "Evicted", "NetworkNotReady", "FailedKillPod",
            "FailedPostStartHook", "FailedPreStopHook", "InspectFailed");

    /** Event reasons that are a real state change but not a failure. */
    static final Set<String> NOTABLE_EVENTS = Set.of(
            "Preempting", "Preempted", "NodeNotReady", "TaintManagerEviction", "Draining");

    /** SIGTERM — a graceful stop, not a crash. */
    private static final int SIGTERM_EXIT = 143;

    /** {@code Unhealthy} fires every probe period; wait for a few before calling it. */
    private static final int PROBE_FAIL_THRESHOLD = 3;

    /**
     * Groups reasons that describe the <em>same</em> underlying failure into one
     * canonical family. A crash-looping container churns through {@code Error} →
     * {@code BackOff} → {@code CrashLoopBackOff} → {@code PodFailed} as it
     * progresses; those are one story, and an incident should investigate them
     * once, not four times. {@code OOMKilled}, {@code ImagePullBackOff}, a config
     * error and so on stay distinct — different causes.
     */
    private static final Map<String, String> FAMILY = Map.ofEntries(
            Map.entry("Error", "CrashLoop"),
            Map.entry("CrashLoopBackOff", "CrashLoop"),
            Map.entry("BackOff", "CrashLoop"),
            Map.entry("PodFailed", "CrashLoop"),
            Map.entry("RunContainerError", "CrashLoop"),
            Map.entry("StartError", "CrashLoop"),
            Map.entry("ContainerCannotRun", "CrashLoop"),
            Map.entry("OOMKilled", "OOM"),
            Map.entry("OOMKilling", "OOM"),
            Map.entry("ImagePullBackOff", "ImagePull"),
            Map.entry("ErrImagePull", "ImagePull"),
            Map.entry("ErrImageNeverPull", "ImagePull"),
            Map.entry("InvalidImageName", "ImagePull"),
            Map.entry("ImageInspectError", "ImagePull"),
            Map.entry("RegistryUnavailable", "ImagePull"),
            Map.entry("CreateContainerConfigError", "Config"),
            Map.entry("CreateContainerError", "Config"),
            Map.entry("FailedMount", "Storage"),
            Map.entry("FailedAttachVolume", "Storage"));

    /** The failure family a triage reason belongs to — the reason itself if it stands alone. */
    public static String family(String reason) {
        return FAMILY.getOrDefault(reason, reason);
    }

    public TriageResult assess(PodState pod) {
        Set<String> reasons = new LinkedHashSet<>();
        Verdict verdict = Verdict.BENIGN;

        // ── container state — strongest signal, holds OOMKilled / exit codes ──
        for (ContainerState c : pod.containers()) {
            String last = c.lastTerminationReason();
            if (last != null && ERROR_TERMINATION.contains(last)) {
                reasons.add(last);
                verdict = Verdict.ERROR;
            }
            if ("terminated".equals(c.state()) && c.exitCode() != null
                    && c.exitCode() != 0 && c.exitCode() != SIGTERM_EXIT) {
                reasons.add(c.reason() != null ? c.reason() : "ExitCode" + c.exitCode());
                verdict = Verdict.ERROR;
            }
            if ("waiting".equals(c.state()) && c.reason() != null && ERROR_WAITING.contains(c.reason())) {
                reasons.add(c.reason());
                verdict = Verdict.ERROR;
            }
        }

        // ── pod phase ────────────────────────────────────────────────────────
        if ("Failed".equals(pod.phase())) {
            reasons.add("PodFailed");
            verdict = Verdict.ERROR;
        }

        // ── events — corroborating ───────────────────────────────────────────
        for (PodEvent e : pod.recentEvents()) {
            if (e.reason() == null) {
                continue;
            }
            if ("Unhealthy".equals(e.reason())) {
                if (e.count() >= PROBE_FAIL_THRESHOLD) {
                    reasons.add("Unhealthy");
                    verdict = Verdict.ERROR;
                }
            } else if (ERROR_EVENTS.contains(e.reason())) {
                reasons.add(e.reason());
                verdict = Verdict.ERROR;
            } else if (NOTABLE_EVENTS.contains(e.reason()) && verdict == Verdict.BENIGN) {
                reasons.add(e.reason());
                verdict = Verdict.NOTABLE;
            }
        }

        if (verdict == Verdict.BENIGN) {
            return TriageResult.benign(pod.name() + " " + pod.phase().toLowerCase() + " — no failure signal");
        }
        return new TriageResult(verdict, reasons, pod.name() + ": " + String.join(", ", reasons));
    }
}
