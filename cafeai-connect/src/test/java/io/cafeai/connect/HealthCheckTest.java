package io.cafeai.connect;

import io.cafeai.core.CafeAI;
import io.cafeai.core.connect.Connection;
import io.cafeai.core.connect.HealthStatus;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code Connect.healthCheck(app)}: the {@code /health} handler over the connections registered with the app. */
@DisplayName("Connect.healthCheck()")
class HealthCheckTest {

    /** A connection whose health is whatever the test says. */
    private record Fake(String name, HealthStatus status) implements Connection {
        @Override public ServiceType type() { return ServiceType.CUSTOM; }
        @Override public HealthStatus probe() { return status; }
        @Override public void register(CafeAI app) { /* nothing to register */ }
    }

    private record Answer(Integer status, Map<String, Object> body) {}

    private static Answer ask(Middleware health) {
        Request req = mock(Request.class);
        Response res = mock(Response.class, RETURNS_SELF);
        when(res.status(anyInt())).thenReturn(res);
        health.handle(req, res, () -> { throw new AssertionError("a health check ends the request"); });

        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(res).json(body.capture());
        Integer code = null;
        try {
            verify(res).status(status.capture());
            code = status.getValue();
        } catch (AssertionError neverCalled) { /* the empty case sends no explicit status */ }
        return new Answer(code, body.getValue());
    }

    @Test @DisplayName("with no connections it answers healthy and empty")
    void none() {
        Answer a = ask(Connect.healthCheck(CafeAI.create()));

        assertThat(a.body()).containsEntry("status", "healthy");
        assertThat((Map<?, ?>) a.body().get("connections")).isEmpty();
    }

    @Test @DisplayName("every connection reachable: 200 healthy, each with its latency")
    void allHealthy() {
        var app = CafeAI.create();
        app.connect(new Fake("redis", HealthStatus.reachable("redis", 4)));
        app.connect(new Fake("ollama", HealthStatus.reachable("ollama", 12)));

        Answer a = ask(Connect.healthCheck(app));

        assertThat(a.status()).isEqualTo(200);
        assertThat(a.body()).containsEntry("status", "healthy");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> conns = (Map<String, Map<String, Object>>) a.body().get("connections");
        assertThat(conns).containsOnlyKeys("redis", "ollama");
        assertThat(conns.get("redis")).containsEntry("state", "REACHABLE").containsEntry("latencyMs", 4L);
    }

    @Test @DisplayName("one connection down: 503, and the response says which and why")
    void oneDown() {
        var app = CafeAI.create();
        app.connect(new Fake("redis", HealthStatus.reachable("redis", 4)));
        app.connect(new Fake("ollama", HealthStatus.unreachable("ollama", "connection refused")));

        Answer a = ask(Connect.healthCheck(app));

        assertThat(a.status()).isEqualTo(503);
        assertThat(a.body()).containsEntry("status", "degraded");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> conns = (Map<String, Map<String, Object>>) a.body().get("connections");
        assertThat(conns.get("ollama")).containsEntry("state", "UNREACHABLE").containsEntry("detail", "connection refused")
            .doesNotContainKey("latencyMs");
    }

    @Test @DisplayName("a degraded connection (running, but not usable) also makes the app 503")
    void degraded() {
        var app = CafeAI.create();
        app.connect(new Fake("ollama", HealthStatus.degraded("ollama", "model not pulled")));

        Answer a = ask(Connect.healthCheck(app));

        assertThat(a.status()).isEqualTo(503);
        assertThat(a.body()).containsEntry("status", "degraded");
    }

    @Test @DisplayName("the status is re-probed on every request, not cached from startup")
    void probedEachTime() {
        var flips = new Connection() {
            int calls;
            @Override public String name() { return "flaky"; }
            @Override public ServiceType type() { return ServiceType.CUSTOM; }
            @Override public HealthStatus probe() {
                return calls++ == 0 ? HealthStatus.reachable("flaky", 1) : HealthStatus.unreachable("flaky", "gone");
            }
            @Override public void register(CafeAI app) { /* nothing */ }
        };
        var app = CafeAI.create();
        app.connect(flips);                                   // startup probe: healthy
        Middleware health = Connect.healthCheck(app);

        assertThat(ask(health).status()).isEqualTo(503);      // the request-time probe sees the change
    }
}
