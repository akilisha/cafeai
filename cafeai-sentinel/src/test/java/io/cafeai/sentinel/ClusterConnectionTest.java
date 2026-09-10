package io.cafeai.sentinel;

import io.fabric8.kubernetes.client.Config;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterConnectionTest {

    @Test
    void ambientYieldsNoExplicitConfig() {
        assertThat(ClusterConnection.ambient().toFabric8Config()).isNull();
    }

    @Test
    void tokenBuildsExplicitConfigWithoutConsultingKubeconfig() {
        Config config = ClusterConnection
                .token("https://api.ocp.example.com:6443", "sha256~secret")
                .toFabric8Config();

        assertThat(config).isNotNull();
        assertThat(config.getMasterUrl()).contains("api.ocp.example.com:6443");
        assertThat(config.getOauthToken()).isEqualTo("sha256~secret");
        assertThat(config.getUsername()).isNull();
        assertThat(config.getAutoConfigure()).isFalse();
    }

    @Test
    void tokenCarriesTlsSettings() {
        Config config = ClusterConnection
                .token("https://api.example.com:6443", "tok")
                .trustCerts(true)
                .caCertFile("/etc/sentinel/ca.crt")
                .toFabric8Config();

        assertThat(config.isTrustCerts()).isTrue();
        assertThat(config.getCaCertFile()).isEqualTo("/etc/sentinel/ca.crt");
    }

    @Test
    void basicAuthSetsUsernameAndPassword() {
        Config config = ClusterConnection
                .basicAuth("https://api.example.com:6443", "watcher", "hunter2")
                .toFabric8Config();

        assertThat(config.getUsername()).isEqualTo("watcher");
        assertThat(config.getPassword()).isEqualTo("hunter2");
        assertThat(config.getOauthToken()).isNull();
        assertThat(config.getAutoConfigure()).isFalse();
    }

    @Test
    void defaultConnectionIsAmbient() {
        assertThat(SentinelConfig.create().connection().toFabric8Config()).isNull();
    }
}
