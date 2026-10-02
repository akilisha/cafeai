package io.cafeai.dev;

import io.cafeai.core.CafeAI;
import io.cafeai.core.spi.CafeAIConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * Sees every CafeAI app as it is created, and which version of the code created it,
 * so a reload can stop exactly the apps the previous version started. Registered as a
 * {@link CafeAIConfigurer}; it configures nothing.
 */
public final class AppTracker implements CafeAIConfigurer {

    private record Tracked(CafeAI app, ClassLoader createdBy) { }

    private static final List<Tracked> APPS = new ArrayList<>();

    @Override
    public void configure(CafeAI app) {
        synchronized (APPS) {
            APPS.add(new Tracked(app, Thread.currentThread().getContextClassLoader()));
        }
    }

    @Override
    public int order() {
        return Integer.MIN_VALUE;   // before any configurer that might fail
    }

    /** Stops every app the code loaded by {@code loader} created, and forgets them. */
    static int stopAppsOf(ClassLoader loader) {
        List<CafeAI> toStop = new ArrayList<>();
        synchronized (APPS) {
            APPS.removeIf(t -> {
                if (t.createdBy() != loader) return false;
                toStop.add(t.app());
                return true;
            });
        }
        for (CafeAI app : toStop) {
            try {
                if (app.isRunning()) app.stop();
            } catch (RuntimeException e) {
                System.out.println("[cafeai-dev] stopping the previous app failed: " + e);
            }
        }
        return toStop.size();
    }
}
