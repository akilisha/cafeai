package io.cafeai.core.login;

import io.cafeai.core.internal.Cli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("cafeai login azure | aws | google: the cloud CLI's own sign-in")
class CloudLoginTest {

    @TempDir Path home;
    private String before;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private final List<List<String>> handedOver = new ArrayList<>();
    private final List<List<String>> captured = new ArrayList<>();
    private Set<String> installed = Set.of("az", "aws", "gcloud");
    private int handOverStatus = 0;
    private Function<List<String>, Cli.Result> answers = command -> new Cli.Result(1, "", "not signed in");

    @BeforeEach
    void isolate() {
        before = System.getProperty("cafeai.config.dir");
        System.setProperty("cafeai.config.dir", home.toString());
        env.put("ANTHROPIC_CONFIG_DIR", home.resolve("anthropic").toString());
        env.put("CLOUDSDK_CONFIG", home.resolve("gcloud").toString());
    }

    @AfterEach
    void restore() {
        if (before == null) System.clearProperty("cafeai.config.dir");
        else System.setProperty("cafeai.config.dir", before);
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        var tools = new CloudLogin.Tools(
                name -> installed.contains(name) ? Optional.of(Path.of("/bin", name)) : Optional.empty(),
                command -> { captured.add(command); return answers.apply(command); },
                command -> { handedOver.add(command); return handOverStatus; });
        var login = new CafeLogin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), p -> null, env::get,
                (cmd, interactive, output) -> { throw new AssertionError("no ant expected"); }, tools);
        return login.run(args);
    }

    private String out() { return out.toString(StandardCharsets.UTF_8); }
    private String err() { return err.toString(StandardCharsets.UTF_8); }

    private static String bin(String name) {
        return Path.of("/bin", name).toString();
    }

    @Test @DisplayName("login azure hands the terminal to az login, then shows who signed in")
    void azure() {
        answers = command -> new Cli.Result(0, "{\"name\":\"Dev subscription\",\"tenantId\":\"t-1\","
                + "\"user\":{\"name\":\"alice@acme.example\",\"type\":\"user\"}}", "");
        assertThat(run("login", "azure", "--tenant", "t-1")).isZero();
        assertThat(handedOver).containsExactly(List.of(bin("az"), "login", "--tenant", "t-1"));
        assertThat(out()).contains("azure: signed in as alice@acme.example, subscription Dev subscription (tenant t-1)");
    }

    @Test @DisplayName("login aws --profile work: aws sso login for that profile; its credentials checked with the same profile")
    void aws() {
        answers = command -> new Cli.Result(0, "{\"Version\":1,\"AccessKeyId\":\"ASIA\",\"SecretAccessKey\":\"s\","
                + "\"SessionToken\":\"t\",\"Expiration\":\"2026-10-10T20:00:00+00:00\"}", "");
        assertThat(run("login", "aws", "--profile", "work")).isZero();
        assertThat(handedOver).containsExactly(List.of(bin("aws"), "sso", "login", "--profile", "work"));
        assertThat(captured).containsExactly(List.of(bin("aws"), "configure", "export-credentials", "--format", "process",
                "--profile", "work"));
        assertThat(out()).contains("aws: signed in, credentials valid until 2026-10-10T20:00:00+00:00");
        assertThat(out()).doesNotContain("ASIA").doesNotContain("\"s\"");
    }

    @Test @DisplayName("login google runs gcloud's application-default login; status reads the file it wrote")
    void google() throws Exception {
        Path gcloud = Files.createDirectories(home.resolve("gcloud"));
        Files.writeString(gcloud.resolve("application_default_credentials.json"), "{\"type\":\"authorized_user\","
                + "\"client_id\":\"c\",\"client_secret\":\"s\",\"refresh_token\":\"r\",\"account\":\"alice@acme.example\","
                + "\"quota_project_id\":\"my-project\"}");
        assertThat(run("login", "google")).isZero();
        assertThat(handedOver).containsExactly(List.of(bin("gcloud"), "auth", "application-default", "login"));
        assertThat(out()).contains("google: application default credentials (authorized_user) for alice@acme.example,"
                + " quota project my-project");
        assertThat(captured).as("gcloud is slow to start; the file is read instead").isEmpty();
    }

    @Test @DisplayName("logout runs each CLI's own sign-out")
    void logout() {
        assertThat(run("logout", "azure")).isZero();
        assertThat(run("logout", "aws", "--profile", "work")).isZero();
        assertThat(run("logout", "google")).isZero();
        assertThat(handedOver).containsExactly(List.of(bin("az"), "logout"),
                List.of(bin("aws"), "sso", "logout", "--profile", "work"),
                List.of(bin("gcloud"), "auth", "application-default", "revoke"));
    }

    @Test @DisplayName("a CLI that isn't installed: where to get it; a failed sign-in: reported, exit 1")
    void failures() {
        installed = Set.of();
        assertThat(run("login", "aws")).isEqualTo(1);
        assertThat(err()).contains("uses the aws CLI, which isn't on the PATH")
                .contains("getting-started-install.html");

        installed = Set.of("az");
        handOverStatus = 1;
        assertThat(run("login", "azure")).isEqualTo(1);
        assertThat(err()).contains("`az login` failed (exit 1)");
    }

    @Test @DisplayName("status has a line per cloud: not installed, not signed in, or signed in")
    void status() {
        installed = Set.of("az");
        assertThat(run("status")).isZero();
        assertThat(out())
                .containsPattern("azure\\s+not signed in \\(cafeai login azure\\)")
                .containsPattern("aws\\s+aws not installed")
                .containsPattern("google\\s+not signed in \\(cafeai login google\\)");
    }
}
