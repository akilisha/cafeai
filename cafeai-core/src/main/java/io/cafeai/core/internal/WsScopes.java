package io.cafeai.core.internal;

import io.cafeai.core.identity.Identity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Carries the upgrade request's scope into a WebSocket connection, so its callbacks run as that
 * request: {@code Identity.current()}, conversation memory, usage and audit records, per-caller
 * credentials.
 *
 * <p>Helidon upgrades as the route of the upgrade request, so {@code onHttpUpgrade} runs inside
 * the app's filters, while the request is still in scope; {@code onOpen} follows on the same
 * connection thread once the filters have returned. The upgrade takes the scope only if it
 * belongs to the very request being upgraded (the same prologue object), and hands it to
 * {@code onOpen}.
 */
final class WsScopes {

    /** Close code for an expired identity: RFC 6455 1008, policy violation. */
    static final int EXPIRED = 1008;

    private static final ThreadLocal<UsageMeter.Scope> UPGRADING = new ThreadLocal<>();

    private static final ScheduledExecutorService EXPIRY = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cafeai-ws-expiry");
        t.setDaemon(true);
        return t;
    });

    private WsScopes() {}

    /** One open connection: the scope it runs in, and its expiry timer. */
    static final class State {
        final UsageMeter.Scope scope;
        volatile boolean closed;
        volatile ScheduledFuture<?> expiry;

        State(UsageMeter.Scope scope) {
            this.scope = scope;
        }

        Optional<Identity> identity() {
            return scope == null ? Optional.empty() : scope.req().identity();
        }

        boolean expired() {
            return identity().map(id -> id.expired(Instant.now())).orElse(false);
        }
    }

    static final Map<Object, State> OPEN = new ConcurrentHashMap<>();

    /** The upgrade of {@code prologue}'s request: take its scope, if the scope is this request's. */
    static void upgrading(Object prologue) {
        UsageMeter.Scope scope = UsageMeter.currentScope();
        if (scope != null && scope.req().helidonServerRequest().prologue() == prologue) UPGRADING.set(scope);
        else UPGRADING.remove();
    }

    /** The connection opened: its state, holding the scope the upgrade took (or none). */
    static State opened(Object session, Runnable closeExpired) {
        UsageMeter.Scope scope = UPGRADING.get();
        UPGRADING.remove();
        State state = new State(scope);
        OPEN.put(session, state);
        state.identity().ifPresent(id -> {
            long delay = Math.max(0, Duration.between(Instant.now(), id.expiresAt()).toMillis());
            state.expiry = EXPIRY.schedule(() -> {
                if (!state.closed) closeExpired.run();
            }, delay, TimeUnit.MILLISECONDS);
        });
        return state;
    }

    /** The connection closed: stop its timer and forget it. */
    static void closed(Object session) {
        State state = OPEN.remove(session);
        if (state != null) {
            state.closed = true;
            if (state.expiry != null) state.expiry.cancel(false);
        }
    }
}
