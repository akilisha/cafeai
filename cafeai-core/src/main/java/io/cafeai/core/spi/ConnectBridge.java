package io.cafeai.core.spi;

import io.cafeai.core.CafeAI;

/**
 * SPI allowing {@code cafeai-connect} to implement {@code app.connect()}
 * without creating a compile-time dependency from {@code cafeai-core}
 * on {@code cafeai-connect}.
 *
 * <p>The {@code Connection} type lives in {@code cafeai-connect}, which
 * depends on {@code cafeai-core}. So {@code cafeai-core} cannot import
 * {@code Connection} directly. This SPI bridge -- registered via
 * {@link java.util.ServiceLoader} -- is the seam.
 *
 * <p>Registered via:
 * {@code META-INF/services/io.cafeai.core.spi.ConnectBridge}
 */
public interface ConnectBridge {

    /**
     * Probes the given connection, registers its capability with the app
     * if reachable, or invokes its fallback policy if not.
     *
     * @param connection an {@code io.cafeai.connect.Connection} instance
     * @param app        the CafeAI application to register into
     */
    void connect(Object connection, CafeAI app);
}
