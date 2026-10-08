package io.cafeai.core.internal;

import io.cafeai.core.routing.Request;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * The request the current thread is working for, so code below a handler (a model call, a
 * credential lookup) can find it without being passed it. It is the same per-request scope that
 * usage metering uses: set while a filter or handler runs, and cleared when it returns.
 *
 * <p>Internal: applications use {@code req}, {@code Identity.current()} or {@code RequestScope}.
 */
public final class CurrentRequest {

    private CurrentRequest() {}

    /** The current request, or empty outside any filter or handler. */
    public static Optional<Request> get() {
        return Optional.ofNullable(UsageMeter.currentRequest());
    }

    /** {@code task}, made to run in the request scope current now, on whatever thread runs it. */
    public static Runnable carry(Runnable task) {
        UsageMeter.Scope captured = UsageMeter.currentScope();
        if (captured == null) return task;
        return () -> {
            try (var in = UsageMeter.enterScope(captured)) {
                task.run();
            }
        };
    }

    /** {@code task}, made to run in the request scope current now, on whatever thread runs it. */
    public static <T> Callable<T> carry(Callable<T> task) {
        UsageMeter.Scope captured = UsageMeter.currentScope();
        if (captured == null) return task;
        return () -> {
            try (var in = UsageMeter.enterScope(captured)) {
                return task.call();
            }
        };
    }
}
