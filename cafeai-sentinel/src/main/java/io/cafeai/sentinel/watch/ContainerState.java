package io.cafeai.sentinel.watch;

/**
 * A single container's state within a {@link PodState} snapshot.
 *
 * <p>When a container is stuck in {@code CrashLoopBackOff}, {@code state} is
 * {@code waiting} and {@code reason} is the back-off symptom — the real cause
 * and exit code of the last run are in {@code lastTerminationReason} /
 * {@code exitCode}, pulled from the container's <em>last</em> terminated state.
 * This is how {@code OOMKilled} (exit 137), which never appears as a Kubernetes
 * Event, is surfaced.
 *
 * @param name                  container name
 * @param ready                 the container's readiness gate
 * @param restartCount          kubelet restart count for this container
 * @param state                 {@code running} | {@code waiting} | {@code terminated} | {@code unknown}
 * @param reason                current waiting/terminated reason (e.g.
 *                              {@code CrashLoopBackOff}, {@code ImagePullBackOff},
 *                              {@code Completed}), or {@code null}
 * @param lastTerminationReason reason of the previous termination when it differs
 *                              from {@code reason} (e.g. {@code OOMKilled},
 *                              {@code Error}), or {@code null}
 * @param exitCode              last termination exit code (current or previous),
 *                              or {@code null}
 * @param message               optional detail from the kubelet, or {@code null}
 */
public record ContainerState(
        String name,
        boolean ready,
        int restartCount,
        String state,
        String reason,
        String lastTerminationReason,
        Integer exitCode,
        String message) {

    /** True when this container is not simply running healthy. */
    public boolean troubled() {
        return !ready
                || "waiting".equals(state)
                || "terminated".equals(state)
                || (exitCode != null && exitCode != 0);
    }

    /** One-line human summary, e.g. {@code "CrashLoopBackOff last=OOMKilled exit=137 restarts=3"}. */
    public String summary() {
        StringBuilder sb = new StringBuilder(reason != null ? reason : state);
        if (lastTerminationReason != null) {
            sb.append(" last=").append(lastTerminationReason);
        }
        if (exitCode != null) {
            sb.append(" exit=").append(exitCode);
        }
        if (restartCount > 0) {
            sb.append(" restarts=").append(restartCount);
        }
        return sb.toString();
    }
}
