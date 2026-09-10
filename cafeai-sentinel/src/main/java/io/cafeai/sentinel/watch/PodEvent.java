package io.cafeai.sentinel.watch;

import java.time.Instant;

/**
 * A Kubernetes {@code core/v1} Event whose {@code involvedObject} is a pod,
 * flattened to the fields the pipeline cares about.
 *
 * @param type     {@code Normal} | {@code Warning}
 * @param reason   short machine reason (e.g. {@code BackOff}, {@code Unhealthy},
 *                 {@code Failed}, {@code FailedScheduling})
 * @param message  human-readable detail
 * @param count    how many times this event has fired (Kubernetes coalesces)
 * @param lastSeen last occurrence, or {@code null} if the timestamp was absent
 */
public record PodEvent(
        String type,
        String reason,
        String message,
        int count,
        Instant lastSeen) {

    /** True for {@code Warning}-type events. */
    public boolean warning() {
        return "Warning".equals(type);
    }
}
