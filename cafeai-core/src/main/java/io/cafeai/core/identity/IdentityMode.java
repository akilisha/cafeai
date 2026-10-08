package io.cafeai.core.identity;

/**
 * Whether this JVM serves verified callers. {@code cafeai-identity} turns it on when its
 * middleware is created; nothing turns it off.
 *
 * <p>Once on, caller-scoped work that runs with no request in scope is refused instead of
 * proceeding unscoped: for example conversation memory used on a thread the request was not
 * carried to (see {@code RequestScope}). Without identity, such work behaves as it always has.
 *
 * <p>JVM-wide, like the rest of an app's process-global wiring: one app per JVM.
 */
public final class IdentityMode {

    private static volatile boolean on;

    private IdentityMode() {}

    /** Turns identity mode on for this JVM. Called by {@code cafeai-identity}. */
    public static void enable() {
        on = true;
    }

    /** Whether identity mode is on. */
    public static boolean enabled() {
        return on;
    }

    /** For tests of the off state in a JVM where another test turned it on. */
    static void resetForTests() {
        on = false;
    }
}
