package io.cafeai.sentinel.cluster;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.Anthropic;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.ai.TokenBudget;
import io.cafeai.sentinel.ClusterConnection;
import io.cafeai.sentinel.ClusterWatch;
import io.cafeai.sentinel.IncidentTracker;
import io.cafeai.sentinel.Investigator;
import io.cafeai.sentinel.SentinelConfig;
import io.cafeai.sentinel.investigate.ClusterInvestigator;
import io.cafeai.sentinel.investigate.IncidentBrief;
import io.cafeai.sentinel.investigate.Investigation;
import io.cafeai.sentinel.investigate.KubeTools;
import io.cafeai.sentinel.investigate.Redactor;
import io.cafeai.sentinel.sink.IncidentJson;
import io.cafeai.sentinel.sink.IncidentSink;
import io.cafeai.sentinel.sink.LogSink;
import io.cafeai.sentinel.sink.SsePublisher;
import io.cafeai.sentinel.sink.WebhookSink;
import io.cafeai.sentinel.watch.ContainerState;
import io.cafeai.sentinel.watch.PodState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Capstone entry point — a runnable {@link io.cafeai.sentinel} pipeline pointed
 * at a Kubernetes / OpenShift cluster.
 *
 * <p><strong>Phase 5 (ROADMAP-18):</strong> {@link ClusterWatch} feeds correlated
 * pod snapshots to an {@link IncidentTracker} that triages and coalesces them
 * into incidents keyed on the owning workload; a {@link ClusterInvestigator}
 * agent (a CafeAI {@code app.agent(...)} with the read-only {@link KubeTools}
 * bundle) investigates each new incident and attaches a structured
 * {@link Investigation}; secrets are redacted at the boundary. Incidents fan out
 * to a {@link LogSink}, an optional {@link WebhookSink}, and an
 * {@link SsePublisher} served over HTTP.
 *
 * <p>Routes ({@code $SENTINEL_PORT}, default 8080):
 * <ul>
 *   <li>{@code GET /}                  — a minimal live dashboard</li>
 *   <li>{@code GET /health}            — status JSON</li>
 *   <li>{@code GET /incidents}         — the currently open incidents as JSON</li>
 *   <li>{@code GET /incidents/stream}  — Server-Sent Events, one frame per lifecycle change</li>
 * </ul>
 *
 * <p>Environment: {@code $SENTINEL_NAMESPACE}; {@code $SENTINEL_API_SERVER} +
 * {@code $SENTINEL_TOKEN} (+ {@code $SENTINEL_CA_CERT_FILE} /
 * {@code $SENTINEL_INSECURE}) for a remote cluster; {@code $SENTINEL_INVESTIGATION_MODEL}
 * / {@code $ANTHROPIC_API_KEY} / {@code $OPENAI_API_KEY} for the model;
 * {@code $SENTINEL_TOKEN_BUDGET_PER_MIN}; {@code $SENTINEL_WEBHOOK_URL}.
 */
public final class ClusterSentinelApp {

    private static final Logger log = LoggerFactory.getLogger(ClusterSentinelApp.class);

    private ClusterSentinelApp() {
    }

    public static void main(String[] args) {
        String namespace = System.getenv().getOrDefault("SENTINEL_NAMESPACE",
                args.length > 0 ? args[0] : "default");
        int port = port();

        SentinelConfig config = SentinelConfig.create()
                .namespace(namespace)
                .connection(connectionFromEnv())
                .tokenBudget(tokenBudgetFromEnv());

        ClusterWatch watch = new ClusterWatch(config);

        CafeAI app = CafeAI.create();
        app.agent("cluster-investigator", ClusterInvestigator.class)
                .model(investigationProvider())
                .tool(new KubeTools(watch.client(), namespace, Redactor.of(config.isRedact())));

        Investigator investigator = incident ->
                app.agent("cluster-investigator", ClusterInvestigator.class, null)
                        .investigate(IncidentBrief.of(incident));

        SsePublisher sse = new SsePublisher();
        List<AutoCloseable> closeables = new ArrayList<>(List.of(watch, sse));

        List<IncidentSink> sinks = new ArrayList<>(List.of(new LogSink(), sse));
        String webhookUrl = System.getenv("SENTINEL_WEBHOOK_URL");
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            WebhookSink webhook = new WebhookSink(webhookUrl.trim());
            sinks.add(webhook);
            closeables.add(webhook);
            log.info("incidents will POST to {}", webhookUrl);
        }

        IncidentTracker tracker = new IncidentTracker(config)
                .onIncident(IncidentSink.of(sinks.toArray(IncidentSink[]::new)))
                .investigator(investigator)
                .start();
        closeables.add(tracker);

        watch.onPodState(state -> {
            logPodState(state);
            tracker.accept(state);
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> close(closeables), "sentinel-shutdown"));

        watch.start();

        app.get("/", (req, res, next) -> res.type("text/html").send(DASHBOARD));
        app.get("/health", (req, res, next) -> res.json(Map.of(
                "status", "ok",
                "namespace", namespace,
                "openIncidents", tracker.openIncidents().size(),
                "sseClients", sse.clientCount())));
        app.get("/incidents", (req, res, next) ->
                res.type("application/json").send(IncidentJson.incidents(tracker.openIncidents())));
        app.get("/incidents/stream", (req, res, next) -> res.stream(sse.stream()));

        log.info("cluster-sentinel Phase 5 — watching '{}', dashboard on http://localhost:{}", namespace, port);
        app.listen(port);
    }

    // ── environment ──────────────────────────────────────────────────────────

    private static int port() {
        String p = System.getenv("SENTINEL_PORT");
        try {
            return p == null || p.isBlank() ? 8080 : Integer.parseInt(p.trim());
        } catch (NumberFormatException e) {
            return 8080;
        }
    }

    private static ClusterConnection connectionFromEnv() {
        String apiServer = System.getenv("SENTINEL_API_SERVER");
        String token = System.getenv("SENTINEL_TOKEN");
        if (apiServer == null || token == null) {
            return ClusterConnection.ambient();
        }
        ClusterConnection conn = ClusterConnection.token(apiServer, token);
        String caCertFile = System.getenv("SENTINEL_CA_CERT_FILE");
        if (caCertFile != null) {
            conn.caCertFile(caCertFile);
        }
        if (Boolean.parseBoolean(System.getenv("SENTINEL_INSECURE"))) {
            conn.trustCerts(true);
        }
        log.info("connecting by token to {}", apiServer);
        return conn;
    }

    private static TokenBudget tokenBudgetFromEnv() {
        String perMin = System.getenv("SENTINEL_TOKEN_BUDGET_PER_MIN");
        if (perMin == null || perMin.isBlank()) {
            return TokenBudget.unlimited();
        }
        try {
            return TokenBudget.perMinute(Long.parseLong(perMin.trim()));
        } catch (IllegalArgumentException e) {
            log.warn("ignoring SENTINEL_TOKEN_BUDGET_PER_MIN='{}': {}", perMin, e.getMessage());
            return TokenBudget.unlimited();
        }
    }

    private static AiProvider investigationProvider() {
        String model = System.getenv("SENTINEL_INVESTIGATION_MODEL");
        if (model != null && !model.isBlank()) {
            return Anthropic.of(model.trim());
        }
        if (System.getenv("ANTHROPIC_API_KEY") != null) {
            return Anthropic.claude35Sonnet();
        }
        if (System.getenv("OPENAI_API_KEY") != null) {
            return OpenAI.gpt4o();
        }
        log.warn("no ANTHROPIC_API_KEY or OPENAI_API_KEY set — investigations will fail until one is");
        return Anthropic.claude35Sonnet();
    }

    // ── misc ─────────────────────────────────────────────────────────────────

    private static void close(List<AutoCloseable> closeables) {
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception e) {
                log.debug("close {} failed: {}", c.getClass().getSimpleName(), e.toString());
            }
        }
    }

    private static void logPodState(PodState state) {
        if (!log.isDebugEnabled()) {
            return;
        }
        String workload = state.workload().toString();
        if (state.deleted()) {
            log.debug("{} :: pod {} deleted", workload, state.name());
            return;
        }
        String trouble = state.containers().stream()
                .filter(ContainerState::troubled)
                .map(c -> c.name() + "(" + c.summary() + ")")
                .collect(Collectors.joining(", "));
        log.debug("{}{} :: pod {} phase={} :: {}",
                state.preExisting() ? "[pre-existing] " : "",
                workload, state.name(), state.phase(),
                trouble.isEmpty() ? "healthy" : trouble);
    }

    private static final String DASHBOARD = """
            <!doctype html><meta charset=utf-8><title>cluster-sentinel</title>
            <style>
              body{font:14px/1.5 system-ui,sans-serif;margin:2rem;max-width:60rem}
              h1{font-size:1.1rem}
              .i{border:1px solid #ccc;border-left-width:4px;border-radius:4px;padding:.6rem .8rem;margin:.5rem 0}
              .ERROR{border-left-color:#c0392b} .NOTABLE{border-left-color:#e67e22}
              .RESOLVED{opacity:.55}
              .m{color:#666;font-size:.85rem} pre{white-space:pre-wrap;margin:.3rem 0 0}
            </style>
            <h1>cluster-sentinel — live incidents</h1>
            <div id=list></div>
            <script>
            const list = document.getElementById('list');
            const cards = new Map();
            function render(inc){
              let el = cards.get(inc.id);
              if(!el){ el = document.createElement('div'); cards.set(inc.id, el); list.prepend(el); }
              el.className = 'i ' + inc.severity + (inc.status === 'RESOLVED' ? ' RESOLVED' : '');
              let h = `<b>${inc.id}</b> [${inc.severity}] ${inc.workload} — ${inc.status}`;
              h += `<div class=m>${inc.reasons.join(', ')} · ${inc.signalCount} signals · pods: ${inc.affectedPods.join(', ')||'—'}</div>`;
              if(inc.investigation){
                const v = inc.investigation;
                h += `<pre><b>${v.category}/${v.confidence}</b> ${v.summary}\\ncause: ${v.likelyCause}\\n`
                   + v.suggestedActions.map(a=>' → '+a).join('\\n') + `</pre>`;
              }
              el.innerHTML = h;
            }
            fetch('/incidents').then(r=>r.json()).then(a=>a.forEach(render));
            new EventSource('/incidents/stream').onmessage = e => render(JSON.parse(e.data).incident);
            </script>
            """;
}
