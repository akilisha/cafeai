package io.cafeai.sentinel.investigate;

import io.cafeai.core.config.ConfigKey;
import io.cafeai.core.config.AppConfig;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The read-only cluster-interrogation tool bundle the {@link ClusterInvestigator}
 * calls. Every method is a lookup — nothing here creates, patches or deletes.
 * All namespaced queries are pinned to the single namespace sentinel watches;
 * node queries are cluster-scoped (the one place the capstone RBAC needs a
 * ClusterRole for {@code nodes}).
 *
 * <p>Every tool output passes through a {@link Redactor} before it is returned,
 * so a secret in a pod's logs or an env value never reaches the LLM prompt or
 * the incident. Outputs are compact plain text, sized for a prompt, and each
 * method catches its own failure and returns a readable message rather than
 * throwing — a dead tool call should inform the agent, not abort the
 * investigation.
 */
public final class KubeTools {

    /** Lines of a container's log the model is shown. */
    public static final ConfigKey<Integer> LOG_LINES = ConfigKey.of(
        "cafeai.sentinel.tool.log.lines", Integer.class, 200,
        "Lines from the end of a container's log the investigation tool returns to the model.");

    /** Events the model is shown for a pod. */
    public static final ConfigKey<Integer> EVENTS = ConfigKey.of(
        "cafeai.sentinel.tool.events", Integer.class, 40,
        "Most events the investigation tool returns to the model for a pod.");

    /** ReplicaSets the model is shown for a deployment. */
    public static final ConfigKey<Integer> REPLICASETS = ConfigKey.of(
        "cafeai.sentinel.tool.replicasets", Integer.class, 8,
        "Most ReplicaSets the investigation tool returns to the model for a deployment.");

    private final int logTailLines = AppConfig.load().positive(LOG_LINES);
    private final int maxEvents = AppConfig.load().positive(EVENTS);
    private final int maxReplicaSets = AppConfig.load().positive(REPLICASETS);

    private final KubernetesClient client;
    private final String namespace;
    private final Redactor redactor;

    public KubeTools(KubernetesClient client, String namespace) {
        this(client, namespace, Redactor.enabled());
    }

    public KubeTools(KubernetesClient client, String namespace, Redactor redactor) {
        this.client = Objects.requireNonNull(client, "client");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
    }

    @Tool("""
            Get one pod's full spec and status as YAML: containers and their images,
            resource requests/limits, probes, volume mounts, env, and the live
            container statuses including lastState (where OOMKilled / exit codes live).
            Use this first for any pod-level failure.
            """)
    public String getPod(@P("the pod name") String name) {
        return redactor.redact(getPodRaw(name));
    }

    @Tool("""
            Get the tail of a container's logs. Set previous=true to read the logs of
            the PRIOR, crashed instance of the container — for a CrashLoopBackOff that
            is where the fatal error is; the current instance usually has none.
            containerName may be empty to use the pod's first container.
            """)
    public String getPodLogs(@P("the pod name") String podName,
                             @P("the container name, or empty for the first container") String containerName,
                             @P("true for the previous (crashed) instance's logs") boolean previous) {
        return redactor.redact(getPodLogsRaw(podName, containerName, previous));
    }

    @Tool("""
            List recent events in the namespace, newest first. Pass an involved object
            name (a pod, deployment, replicaset, ...) to filter to just that object,
            or empty for all. Events name missing ConfigMaps/Secrets, scheduling
            failures, probe failures, image pull errors.
            """)
    public String listEvents(@P("an involved object name to filter by, or empty for all") String involvedObjectName) {
        return redactor.redact(listEventsRaw(involvedObjectName));
    }

    @Tool("""
            Describe a Deployment: desired/ready/updated/available replicas, rollout
            strategy, status conditions (Available, Progressing — including
            ProgressDeadlineExceeded), and per-container images and resource
            requests/limits.
            """)
    public String describeDeployment(@P("the deployment name") String name) {
        return redactor.redact(describeDeploymentRaw(name));
    }

    @Tool("""
            The ReplicaSet history of a Deployment, newest revision first: revision
            number, container images, and desired/ready replica counts. Use this to
            see whether a recent rollout introduced the failure.
            """)
    public String getReplicaSetHistory(@P("the deployment name") String deploymentName) {
        return redactor.redact(getReplicaSetHistoryRaw(deploymentName));
    }

    @Tool("""
            Node conditions for all nodes, or one node by name (empty for all):
            Ready, plus MemoryPressure / DiskPressure / PIDPressure when set. Use this
            when a pod is Pending (FailedScheduling) or was Evicted.
            """)
    public String getNodeConditions(@P("a node name, or empty for all nodes") String nodeName) {
        return redactor.redact(getNodeConditionsRaw(nodeName));
    }

    @Tool("""
            The ResourceQuota usage and LimitRange defaults in the namespace — the
            ceilings a pod is scheduled and OOM-checked against. Use this for
            FailedScheduling (exceeded quota) or OOMKilled (default/max memory).
            """)
    public String getResourceQuota() {
        return redactor.redact(getResourceQuotaRaw());
    }

    // ── raw lookups ──────────────────────────────────────────────────────────

    private String getPodRaw(String name) {
        try {
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            if (pod == null) {
                return "Pod " + namespace + "/" + name + " not found (it may have been replaced — "
                        + "call listEvents or getReplicaSetHistory).";
            }
            if (pod.getMetadata() != null) {
                pod.getMetadata().setManagedFields(null);
            }
            return client.getKubernetesSerialization().asYaml(pod);
        } catch (RuntimeException e) {
            return "getPod failed: " + e.getMessage();
        }
    }

    private String getPodLogsRaw(String podName, String containerName, boolean previous) {
        try {
            String container = resolveContainer(podName, containerName);
            if (container == null) {
                return "Pod " + namespace + "/" + podName + " not found.";
            }
            var loggable = client.pods().inNamespace(namespace).withName(podName).inContainer(container);
            String log = previous
                    ? loggable.terminated().tailingLines(logTailLines).getLog()
                    : loggable.tailingLines(logTailLines).getLog();
            if (log == null || log.isBlank()) {
                return "(no " + (previous ? "previous " : "") + "logs for " + podName + "/" + container + ")";
            }
            return "logs " + podName + "/" + container + (previous ? " (previous instance)" : "") + ":\n" + log;
        } catch (RuntimeException e) {
            return "getPodLogs failed: " + e.getMessage()
                    + (previous ? " (there may be no previous instance yet)" : "");
        }
    }

    private String listEventsRaw(String involvedObjectName) {
        try {
            List<Event> events = new ArrayList<>(
                    client.v1().events().inNamespace(namespace).list().getItems());
            if (involvedObjectName != null && !involvedObjectName.isBlank()) {
                String want = involvedObjectName.trim();
                events.removeIf(e -> e.getInvolvedObject() == null
                        || !want.equals(e.getInvolvedObject().getName()));
            }
            events.sort(Comparator.comparing(KubeTools::eventTime,
                    Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
            if (events.isEmpty()) {
                return "(no events)";
            }
            StringBuilder sb = new StringBuilder();
            for (Event e : events.subList(0, Math.min(events.size(), maxEvents))) {
                String obj = e.getInvolvedObject() == null ? "?"
                        : e.getInvolvedObject().getKind() + "/" + e.getInvolvedObject().getName();
                sb.append(nz(e.getType())).append(' ')
                        .append(nz(e.getReason())).append(" x").append(e.getCount() == null ? 1 : e.getCount())
                        .append("  ").append(obj)
                        .append("  ").append(nz(e.getMessage()).replace('\n', ' ')).append('\n');
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "listEvents failed: " + e.getMessage();
        }
    }

    private String describeDeploymentRaw(String name) {
        try {
            Deployment d = client.apps().deployments().inNamespace(namespace).withName(name).get();
            if (d == null) {
                return "Deployment " + namespace + "/" + name + " not found.";
            }
            StringBuilder sb = new StringBuilder("Deployment/").append(name).append('\n');
            if (d.getSpec() != null) {
                sb.append("desired replicas: ").append(d.getSpec().getReplicas()).append('\n');
                if (d.getSpec().getStrategy() != null) {
                    sb.append("strategy: ").append(d.getSpec().getStrategy().getType()).append('\n');
                }
            }
            if (d.getStatus() != null) {
                sb.append("status: ready=").append(nz(d.getStatus().getReadyReplicas()))
                        .append(" updated=").append(nz(d.getStatus().getUpdatedReplicas()))
                        .append(" available=").append(nz(d.getStatus().getAvailableReplicas()))
                        .append(" unavailable=").append(nz(d.getStatus().getUnavailableReplicas())).append('\n');
                List<DeploymentCondition> conditions = d.getStatus().getConditions();
                if (conditions != null) {
                    for (DeploymentCondition c : conditions) {
                        sb.append("condition ").append(c.getType()).append('=').append(c.getStatus())
                                .append(" (").append(nz(c.getReason())).append("): ")
                                .append(nz(c.getMessage())).append('\n');
                    }
                }
            }
            appendContainers(sb, podContainers(d));
            return sb.toString();
        } catch (RuntimeException e) {
            return "describeDeployment failed: " + e.getMessage();
        }
    }

    private String getReplicaSetHistoryRaw(String deploymentName) {
        try {
            List<ReplicaSet> owned = new ArrayList<>();
            for (ReplicaSet rs : client.apps().replicaSets().inNamespace(namespace).list().getItems()) {
                if (ownedBy(rs.getMetadata() == null ? null : rs.getMetadata().getOwnerReferences(),
                        "Deployment", deploymentName)) {
                    owned.add(rs);
                }
            }
            if (owned.isEmpty()) {
                return "No ReplicaSets found for Deployment/" + deploymentName + ".";
            }
            owned.sort(Comparator.comparing(KubeTools::revision).reversed());
            StringBuilder sb = new StringBuilder("ReplicaSet history for Deployment/")
                    .append(deploymentName).append('\n');
            for (ReplicaSet rs : owned.subList(0, Math.min(owned.size(), maxReplicaSets))) {
                String images = "?";
                if (rs.getSpec() != null && rs.getSpec().getTemplate() != null
                        && rs.getSpec().getTemplate().getSpec() != null) {
                    images = String.join(", ",
                            containerImages(rs.getSpec().getTemplate().getSpec().getContainers()));
                }
                int ready = rs.getStatus() == null || rs.getStatus().getReadyReplicas() == null
                        ? 0 : rs.getStatus().getReadyReplicas();
                Integer desired = rs.getSpec() == null ? null : rs.getSpec().getReplicas();
                sb.append("revision ").append(revision(rs))
                        .append("  ").append(rs.getMetadata().getName())
                        .append("  replicas ").append(ready).append('/').append(nz(desired))
                        .append("  images: ").append(images).append('\n');
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "getReplicaSetHistory failed: " + e.getMessage();
        }
    }

    private String getNodeConditionsRaw(String nodeName) {
        try {
            List<Node> nodes = new ArrayList<>();
            if (nodeName != null && !nodeName.isBlank()) {
                Node n = client.nodes().withName(nodeName.trim()).get();
                if (n != null) {
                    nodes.add(n);
                }
            } else {
                nodes.addAll(client.nodes().list().getItems());
            }
            if (nodes.isEmpty()) {
                return "(no nodes" + (nodeName == null || nodeName.isBlank() ? "" : " named " + nodeName) + ")";
            }
            StringBuilder sb = new StringBuilder();
            for (Node n : nodes) {
                sb.append("Node/").append(n.getMetadata().getName()).append(": ");
                List<String> parts = new ArrayList<>();
                if (n.getStatus() != null && n.getStatus().getConditions() != null) {
                    for (NodeCondition c : n.getStatus().getConditions()) {
                        boolean pressure = c.getType() != null && c.getType().endsWith("Pressure");
                        boolean notReady = "Ready".equals(c.getType()) && !"True".equals(c.getStatus());
                        boolean pressured = pressure && "True".equals(c.getStatus());
                        if (notReady || pressured || "Ready".equals(c.getType())) {
                            parts.add(c.getType() + "=" + c.getStatus());
                        }
                    }
                }
                sb.append(parts.isEmpty() ? "no conditions reported" : String.join(", ", parts)).append('\n');
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return "getNodeConditions failed: " + e.getMessage();
        }
    }

    private String getResourceQuotaRaw() {
        try {
            StringBuilder sb = new StringBuilder();
            List<ResourceQuota> quotas = client.resourceQuotas().inNamespace(namespace).list().getItems();
            if (quotas.isEmpty()) {
                sb.append("(no ResourceQuota in ").append(namespace).append(")\n");
            }
            for (ResourceQuota q : quotas) {
                sb.append("ResourceQuota/").append(q.getMetadata().getName()).append('\n');
                if (q.getStatus() != null) {
                    Map<String, Quantity> hard = q.getStatus().getHard();
                    Map<String, Quantity> used = q.getStatus().getUsed();
                    if (hard != null) {
                        for (Map.Entry<String, Quantity> e : hard.entrySet()) {
                            Quantity u = used == null ? null : used.get(e.getKey());
                            sb.append("  ").append(e.getKey()).append(": used ")
                                    .append(u == null ? "?" : u.toString())
                                    .append(" / ").append(e.getValue().toString()).append('\n');
                        }
                    }
                }
            }
            for (LimitRange lr : client.limitRanges().inNamespace(namespace).list().getItems()) {
                sb.append("LimitRange/").append(lr.getMetadata().getName()).append('\n');
                if (lr.getSpec() != null && lr.getSpec().getLimits() != null) {
                    lr.getSpec().getLimits().forEach(item ->
                            sb.append("  ").append(item.getType())
                                    .append(" default=").append(item.getDefault())
                                    .append(" defaultRequest=").append(item.getDefaultRequest())
                                    .append(" max=").append(item.getMax()).append('\n'));
                }
            }
            return sb.isEmpty() ? "(no quota or limit range)" : sb.toString();
        } catch (RuntimeException e) {
            return "getResourceQuota failed: " + e.getMessage();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String resolveContainer(String podName, String containerName) {
        if (containerName != null && !containerName.isBlank()) {
            return containerName.trim();
        }
        Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null || pod.getSpec() == null || pod.getSpec().getContainers().isEmpty()) {
            return pod == null ? null : "";
        }
        return pod.getSpec().getContainers().get(0).getName();
    }

    private List<Container> podContainers(Deployment d) {
        if (d.getSpec() == null || d.getSpec().getTemplate() == null
                || d.getSpec().getTemplate().getSpec() == null) {
            return List.of();
        }
        return d.getSpec().getTemplate().getSpec().getContainers();
    }

    private static void appendContainers(StringBuilder sb, List<Container> containers) {
        for (Container c : containers) {
            sb.append("container ").append(c.getName()).append(": image=").append(c.getImage());
            if (c.getResources() != null) {
                sb.append("  requests=").append(c.getResources().getRequests())
                        .append("  limits=").append(c.getResources().getLimits());
            }
            sb.append('\n');
        }
    }

    private static List<String> containerImages(List<Container> containers) {
        List<String> out = new ArrayList<>();
        if (containers != null) {
            for (Container c : containers) {
                out.add(c.getImage());
            }
        }
        return out;
    }

    private static boolean ownedBy(List<OwnerReference> refs, String kind, String name) {
        if (refs == null) {
            return false;
        }
        return refs.stream().anyMatch(r -> kind.equals(r.getKind()) && name.equals(r.getName()));
    }

    private static long revision(ReplicaSet rs) {
        if (rs.getMetadata() == null || rs.getMetadata().getAnnotations() == null) {
            return -1;
        }
        try {
            return Long.parseLong(rs.getMetadata().getAnnotations()
                    .getOrDefault("deployment.kubernetes.io/revision", "-1"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String eventTime(Event e) {
        if (e.getLastTimestamp() != null) {
            return e.getLastTimestamp();
        }
        if (e.getEventTime() != null) {
            return e.getEventTime().getTime();
        }
        return e.getMetadata() == null ? null : e.getMetadata().getCreationTimestamp();
    }

    private static String nz(Object o) {
        return o == null ? "" : o.toString();
    }
}
