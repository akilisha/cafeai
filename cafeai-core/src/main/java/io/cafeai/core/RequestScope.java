package io.cafeai.core;

import io.cafeai.core.internal.CurrentRequest;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * Carries the current request onto other threads.
 *
 * <p>While a filter or handler runs, CafeAI knows which request the thread is working for, and
 * code below it relies on that: {@code Identity.current()}, conversation memory scoped to the
 * caller, usage and audit records. Work a handler hands to another thread loses it, unless it is
 * carried:
 *
 * <pre>{@code
 *   // A parallel agentic workflow runs its agents on an executor:
 *   AgenticServices.parallelBuilder()
 *       .subAgents(market, risk)
 *       .executor(RequestScope.carrying(Executors.newVirtualThreadPerTaskExecutor()))
 *       .build();
 *
 *   // Or one task:
 *   pool.submit(RequestScope.wrap(() -> summarise(order)));
 * }</pre>
 *
 * <p>CafeAI's own streams already run in their request's scope.
 */
public final class RequestScope {

    private RequestScope() {}

    /** {@code task}, made to run in the request scope current now. Unchanged outside any request. */
    public static Runnable wrap(Runnable task) {
        return CurrentRequest.carry(Objects.requireNonNull(task, "task"));
    }

    /** {@code task}, made to run in the request scope current now. Unchanged outside any request. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        return CurrentRequest.carry(Objects.requireNonNull(task, "task"));
    }

    /**
     * An executor that runs each task in the request scope current when the task was submitted.
     */
    public static Executor carrying(Executor executor) {
        Objects.requireNonNull(executor, "executor");
        return command -> executor.execute(wrap(command));
    }
}
