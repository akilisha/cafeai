package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import io.cafeai.core.Locals;
import io.cafeai.core.spi.ConnectBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * ServiceLoader implementation of {@link ConnectBridge}.
 *
 * <p>Receives an {@code Object} from {@code cafeai-core}'s {@code app.connect()},
 * casts it to {@link Connection}, probes the service, then either calls
 * {@link Connection#register(CafeAI)} or invokes the fallback policy.
 *
 * <p>Also maintains the connection registry in
 * {@code app.local(Locals.CONNECTIONS)} so {@link Connect#healthCheck(CafeAI)}
 * can probe them on demand.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ConnectBridge}
 */
public final class CafeAIConnectBridge implements ConnectBridge {

    private static final Logger log = LoggerFactory.getLogger(CafeAIConnectBridge.class);

    @Override
    public void connect(Object connectionObj, CafeAI app) {
        if (!(connectionObj instanceof Connection connection)) {
            throw new IllegalArgumentException(
                "app.connect() requires an io.cafeai.connect.Connection instance. " +
                "Got: " + connectionObj.getClass().getName() + "\n" +
                "Use one of: Redis.at(), Ollama.at(), PgVector.at(), " +
                "Connect.fromEnv(), or implement Connection directly.");
        }

        // Register in the connection list for health checks
        registerInLocals(app, connection);

        // Probe the service
        log.info("Probing {}...", connection.name());
        HealthStatus status = connection.probe();
        log.info("  {}", status);

        if (status.isHealthy()) {
            // Service is reachable -- wire its capability into the app
            connection.register(app);
        } else {
            // Service is unreachable or degraded -- invoke fallback policy
            connection.fallback().onUnavailable(status, app);
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerInLocals(CafeAI app, Connection connection) {
        List<Object> connections = (List<Object>) app.local(Locals.CONNECTIONS);
        if (connections == null) {
            connections = new ArrayList<>();
        }
        connections.add(connection);
        app.local(Locals.CONNECTIONS, connections);
    }
}
