package io.cafeai.core.internal;

import io.cafeai.core.routing.Request;

import java.util.Optional;

/**
 * The request the current thread is working for, so code below a handler (a model call, a
 * credential lookup) can find it without being passed it. It is the same per-request scope that
 * usage metering uses: set while a filter or handler runs, and cleared when it returns.
 *
 * <p>Internal: applications use {@code req} or {@code Identity.current()}.
 */
public final class CurrentRequest {

    private CurrentRequest() {}

    /** The current request, or empty outside any filter or handler. */
    public static Optional<Request> get() {
        return Optional.ofNullable(UsageMeter.currentRequest());
    }
}
