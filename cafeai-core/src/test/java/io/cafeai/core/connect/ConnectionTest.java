package io.cafeai.core.connect;

import io.cafeai.core.CafeAI;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@code app.connect(...)} decides between {@code register} and the {@link Fallback} from a probe;
 * {@link Fallback}'s strategies and {@link HealthStatus} are what it decides with.
 */
@DisplayName("Connection, HealthStatus and Fallback")
class ConnectionTest {

    /** A connection that records what the app did with it. */
    private static final class Probe implements Connection {
        final HealthStatus status;
        final List<String> events = new ArrayList<>();
        Fallback fallback;
        Probe(HealthStatus status) { this.status = status; }

        @Override public String name() { return "probe"; }
        @Override public ServiceType type() { return ServiceType.CUSTOM; }
        @Override public HealthStatus probe() { events.add("probe"); return status; }
        @Override public void register(CafeAI app) { events.add("register"); }
        @Override public Fallback fallback() { return fallback != null ? fallback : Connection.super.fallback(); }
    }

    // -- HealthStatus ----------------------------------------------------------------------------

    @Nested @DisplayName("HealthStatus")
    class Status {
        @Test @DisplayName("three states; only REACHABLE is healthy")
        void states() {
            assertThat(HealthStatus.reachable("s", 3).isHealthy()).isTrue();
            assertThat(HealthStatus.unreachable("s", "why").isHealthy()).isFalse();
            assertThat(HealthStatus.degraded("s", "why").isHealthy()).isFalse();
        }

        @Test @DisplayName("latency is measured only for a reachable service")
        void latency() {
            assertThat(HealthStatus.reachable("s", 42).latencyMs()).isEqualTo(42);
            assertThat(HealthStatus.unreachable("s", "x").latencyMs()).isNegative();
            assertThat(HealthStatus.degraded("s", "x").latencyMs()).isNegative();
        }

        @Test @DisplayName("toString reads as a sentence, with the reason when there is one")
        void text() {
            assertThat(HealthStatus.reachable("redis", 7)).hasToString("redis: reachable (7ms)");
            assertThat(HealthStatus.unreachable("redis", "refused")).hasToString("redis: unreachable -- refused");
            assertThat(HealthStatus.degraded("ollama", "no model")).hasToString("ollama: degraded -- no model");
        }

        @Test @DisplayName("it records when it was taken")
        void timestamp() {
            assertThat(HealthStatus.reachable("s", 1).checkedAt()).isNotNull();
        }
    }

    // -- app.connect() -----------------------------------------------------------------------------

    @Nested @DisplayName("app.connect()")
    class Connect {
        @Test @DisplayName("a healthy service is probed once, then registered")
        void healthy() {
            var conn = new Probe(HealthStatus.reachable("probe", 1));

            CafeAI.create().connect(conn);

            assertThat(conn.events).containsExactly("probe", "register");
        }

        @Test @DisplayName("an unhealthy service is not registered; its fallback is told, with the status and the app")
        void unhealthy() {
            var conn = new Probe(HealthStatus.unreachable("probe", "refused"));
            var told = new ArrayList<HealthStatus>();
            conn.fallback = (status, app) -> told.add(status);

            CafeAI.create().connect(conn);

            assertThat(conn.events).containsExactly("probe");
            assertThat(told).singleElement().satisfies(s -> assertThat(s.detail()).isEqualTo("refused"));
        }

        @Test @DisplayName("a degraded service takes the fallback path too")
        void degraded() {
            var conn = new Probe(HealthStatus.degraded("probe", "model missing"));
            var told = new ArrayList<HealthStatus>();
            conn.fallback = (status, app) -> told.add(status);

            CafeAI.create().connect(conn);

            assertThat(conn.events).doesNotContain("register");
            assertThat(told).hasSize(1);
        }

        @Test @DisplayName("by default the app carries on when a service is down")
        void defaultCarriesOn() {
            assertThatCode(() -> CafeAI.create().connect(new Probe(HealthStatus.unreachable("probe", "x"))))
                .doesNotThrowAnyException();
        }

        @Test @DisplayName("failFast() stops startup, and the message says how to opt out")
        void failFast() {
            var conn = new Probe(HealthStatus.unreachable("probe", "refused"));
            conn.fallback = Fallback.failFast();

            assertThatThrownBy(() -> CafeAI.create().connect(conn))
                .isInstanceOf(Fallback.ServiceUnavailableException.class)
                .hasMessageContaining("probe: unreachable -- refused")
                .hasMessageContaining("Fallback.warnAndContinue()");
        }

        @Test @DisplayName("a null connection is refused")
        void nullConnection() {
            assertThatThrownBy(() -> CafeAI.create().connect(null)).isInstanceOf(NullPointerException.class);
        }

        @Test @DisplayName("onUnavailable() wraps a connection without changing what it is")
        void onUnavailable() {
            var inner = new Probe(HealthStatus.reachable("probe", 5));
            Fallback custom = (status, app) -> { };

            Connection wrapped = inner.onUnavailable(custom);

            assertThat(wrapped.name()).isEqualTo("probe");
            assertThat(wrapped.type()).isEqualTo(Connection.ServiceType.CUSTOM);
            assertThat(wrapped.fallback()).isSameAs(custom);
            assertThat(wrapped.probe().isHealthy()).isTrue();
            wrapped.register(mock(CafeAI.class));
            assertThat(inner.events).containsExactly("probe", "register");
        }
    }

    // -- Fallback strategies -----------------------------------------------------------------------

    @Nested @DisplayName("Fallback")
    class Strategies {
        private final HealthStatus down = HealthStatus.unreachable("svc", "refused");

        @Test @DisplayName("warnAndContinue() and ignore() do nothing to the app")
        void doNothing() {
            CafeAI app = mock(CafeAI.class);

            Fallback.warnAndContinue().onUnavailable(down, app);
            Fallback.ignore().onUnavailable(down, app);

            verifyNoInteractions(app);
        }

        @Test @DisplayName("use() registers the alternative as the right kind of capability")
        void use() {
            CafeAI app = mock(CafeAI.class);
            MemoryStrategy memory = MemoryStrategy.inMemory();

            Fallback.use(memory).onUnavailable(down, app);

            verify(app).memory(memory);
        }

        @Test @DisplayName("use() refuses something that is not a CafeAI capability")
        void useUnknown() {
            assertThatThrownBy(() -> Fallback.use("not a capability").onUnavailable(down, mock(CafeAI.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a recognized CafeAI capability");
        }

        @Test @DisplayName("connectInstead() registers another connection with the app")
        void connectInstead() {
            CafeAI app = mock(CafeAI.class);
            var alternative = new Probe(HealthStatus.reachable("alt", 1));

            Fallback.connectInstead(alternative).onUnavailable(down, app);

            assertThat(alternative.events).containsExactly("register");
        }

        @Test @DisplayName("connectInstead() also accepts a plain capability")
        void connectInsteadCapability() {
            CafeAI app = mock(CafeAI.class);
            MemoryStrategy memory = MemoryStrategy.inMemory();

            Fallback.connectInstead(memory).onUnavailable(down, app);

            verify(app).memory(memory);
        }
    }
}
