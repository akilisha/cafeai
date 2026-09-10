package io.cafeai.sentinel;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;

import java.util.Objects;

/**
 * How sentinel connects to the cluster API server.
 *
 * <p>The default — {@link #ambient()} — is the current kubeconfig context (or the
 * mounted ServiceAccount token when running in-cluster). That is right for a
 * laptop against minikube and for a pod inside the target cluster, but not for
 * the common enterprise case: a sentinel process on a bastion / CI runner /
 * separate cluster that holds a bearer token for the cluster it watches. For
 * that, give it the API server URL and a token explicitly:
 *
 * <pre>{@code
 *   var config = SentinelConfig.create()
 *       .namespace("payments")
 *       .connection(ClusterConnection
 *           .token("https://api.ocp.example.com:6443", System.getenv("SENTINEL_TOKEN"))
 *           .caCertFile("/etc/sentinel/ca.crt"));
 * }</pre>
 *
 * <p>Four modes:
 * <ul>
 *   <li>{@link #ambient()} — kubeconfig current-context / in-cluster config</li>
 *   <li>{@link #context(String)} — a named context from the ambient kubeconfig</li>
 *   <li>{@link #token(String, String)} — bearer / OAuth token against an explicit
 *       API server URL <em>(the enterprise default)</em></li>
 *   <li>{@link #basicAuth(String, String, String)} — HTTP basic auth; rejected by
 *       Kubernetes &ge; 1.19, kept only for the rare legacy / custom endpoint</li>
 * </ul>
 *
 * <p>{@link #trustCerts(boolean)} / {@link #caCertFile(String)} /
 * {@link #caCertData(String)} tune TLS for the two explicit modes; they are
 * ignored for {@code ambient} / {@code context}, which take their TLS settings
 * from the kubeconfig.
 */
public final class ClusterConnection {

    private enum Mode { AMBIENT, CONTEXT, TOKEN, BASIC_AUTH }

    private final Mode mode;
    private final String context;
    private final String apiServerUrl;
    private final String oauthToken;
    private final String username;
    private final String password;

    private boolean trustCerts = false;
    private String caCertFile;
    private String caCertData;

    private ClusterConnection(Mode mode, String context, String apiServerUrl,
                              String oauthToken, String username, String password) {
        this.mode = mode;
        this.context = context;
        this.apiServerUrl = apiServerUrl;
        this.oauthToken = oauthToken;
        this.username = username;
        this.password = password;
    }

    /** Ambient kubeconfig current-context, or the in-cluster ServiceAccount token. */
    public static ClusterConnection ambient() {
        return new ClusterConnection(Mode.AMBIENT, null, null, null, null, null);
    }

    /** A named context from the ambient kubeconfig, instead of its current-context. */
    public static ClusterConnection context(String context) {
        return new ClusterConnection(Mode.CONTEXT,
                Objects.requireNonNull(context, "context"), null, null, null, null);
    }

    /**
     * Bearer / OAuth token against an explicit API server URL — no kubeconfig
     * consulted. The usual enterprise / OpenShift setup: {@code oc whoami -t} or
     * a ServiceAccount token, plus {@code oc whoami --show-server}.
     */
    public static ClusterConnection token(String apiServerUrl, String oauthToken) {
        return new ClusterConnection(Mode.TOKEN, null,
                Objects.requireNonNull(apiServerUrl, "apiServerUrl"),
                Objects.requireNonNull(oauthToken, "oauthToken"), null, null);
    }

    /**
     * HTTP basic auth against an explicit API server URL. Kubernetes dropped
     * static-password auth in 1.19, so this only reaches a purpose-built or
     * proxied endpoint — {@link #token(String, String)} is almost always what
     * you want.
     */
    public static ClusterConnection basicAuth(String apiServerUrl, String username, String password) {
        return new ClusterConnection(Mode.BASIC_AUTH, null,
                Objects.requireNonNull(apiServerUrl, "apiServerUrl"), null,
                Objects.requireNonNull(username, "username"),
                Objects.requireNonNull(password, "password"));
    }

    /**
     * Skip TLS verification of the API server certificate. For dev / staging
     * clusters with a self-signed cert; never production — provide the CA via
     * {@link #caCertFile(String)} instead. Ignored for {@code ambient} / {@code context}.
     */
    public ClusterConnection trustCerts(boolean trustCerts) {
        this.trustCerts = trustCerts;
        return this;
    }

    /**
     * Path to a PEM file holding the CA bundle that signs the API server
     * certificate — the corporate CA on an enterprise cluster. Ignored for
     * {@code ambient} / {@code context}.
     */
    public ClusterConnection caCertFile(String caCertFile) {
        this.caCertFile = caCertFile;
        return this;
    }

    /**
     * The CA bundle contents (PEM, raw or base64), as an alternative to
     * {@link #caCertFile(String)} — e.g. from a mounted Secret or env var.
     */
    public ClusterConnection caCertData(String caCertData) {
        this.caCertData = caCertData;
        return this;
    }

    /**
     * The fabric8 {@link Config} for this connection, or {@code null} to let the
     * client auto-configure from the ambient environment (the {@code ambient} mode).
     */
    Config toFabric8Config() {
        return switch (mode) {
            case AMBIENT -> null;
            case CONTEXT -> Config.autoConfigure(context);
            case TOKEN -> explicitBase().withOauthToken(oauthToken).build();
            case BASIC_AUTH -> explicitBase().withUsername(username).withPassword(password).build();
        };
    }

    private ConfigBuilder explicitBase() {
        ConfigBuilder builder = new ConfigBuilder()
                .withAutoConfigure(false)
                .withMasterUrl(apiServerUrl)
                .withTrustCerts(trustCerts);
        if (caCertFile != null) {
            builder.withCaCertFile(caCertFile);
        }
        if (caCertData != null) {
            builder.withCaCertData(caCertData);
        }
        return builder;
    }
}
