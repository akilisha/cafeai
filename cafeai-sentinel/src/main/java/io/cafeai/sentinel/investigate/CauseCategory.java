package io.cafeai.sentinel.investigate;

/**
 * The class of root cause an {@link Investigation} settled on. Coarse on purpose —
 * enough for a sink to route or group incidents, not a taxonomy.
 */
public enum CauseCategory {

    /** Image cannot be pulled — wrong tag, private registry, no pull secret. */
    IMAGE,

    /** Missing or malformed config — absent ConfigMap/Secret, bad env, bad args. */
    CONFIG,

    /** Resource limits — OOMKill, CPU throttling, ephemeral-storage eviction. */
    RESOURCES,

    /** Pod cannot be scheduled — insufficient capacity, taints, affinity, quota. */
    SCHEDULING,

    /** Readiness / liveness / startup probe failing. */
    PROBE,

    /** Volume / PVC — bind failure, mount failure, permissions. */
    STORAGE,

    /** Networking — CNI, DNS, service/endpoint, network policy. */
    NETWORK,

    /** The application itself exits non-zero — a bug or bad startup logic. */
    APPLICATION,

    /** RBAC / SCC / PodSecurity denials. */
    PERMISSIONS,

    /** An external dependency the workload needs is down or unreachable. */
    DEPENDENCY,

    /** Evidence was insufficient to name a cause. */
    UNKNOWN
}
