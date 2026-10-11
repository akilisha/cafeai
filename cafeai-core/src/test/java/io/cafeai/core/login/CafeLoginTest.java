package io.cafeai.core.login;

import io.cafeai.core.internal.KeyVendor;
import io.cafeai.core.internal.SavedKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("cafeai login / logout / status")
class CafeLoginTest {

    @TempDir Path home;
    private String before;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> env = new HashMap<>();
    private String typed;
    private String prompt;
    private ClaudeLogin.Processes processes = (cmd, interactive, output) -> {
        throw new AssertionError("no process expected: " + cmd);
    };

    @BeforeEach
    void useTempDir() {
        before = System.getProperty("cafeai.config.dir");
        System.setProperty("cafeai.config.dir", home.toString());
        env.put("ANTHROPIC_CONFIG_DIR", home.resolve("anthropic").toString());
    }

    @AfterEach
    void restore() {
        if (before == null) System.clearProperty("cafeai.config.dir");
        else System.setProperty("cafeai.config.dir", before);
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        var login = new CafeLogin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                p -> { prompt = p; return typed; }, env::get, (cmd, interactive, output) -> processes.run(cmd, interactive, output));
        return login.run(args);
    }

    private String out() { return out.toString(StandardCharsets.UTF_8); }
    private String err() { return err.toString(StandardCharsets.UTF_8); }

    @Test @DisplayName("login saves the key, shows only its last four characters, and points to the keys page")
    void login() {
        typed = "  sk-test-abcdefgh1234 \n";
        assertThat(run("login", "openai")).isZero();

        assertThat(SavedKeys.saved(KeyVendor.OPENAI)).isEqualTo("sk-test-abcdefgh1234");
        assertThat(prompt).isEqualTo("Paste the key: ");
        assertThat(out()).contains("https://platform.openai.com/api-keys").contains("****1234")
                .doesNotContain("abcdefgh");
    }

    @Test @DisplayName("vendor names ignore case")
    void caseInsensitive() {
        typed = "sk-x-12345678";
        assertThat(run("login", "DeepSeek")).isZero();
        assertThat(SavedKeys.saved(KeyVendor.DEEPSEEK)).isEqualTo("sk-x-12345678");
    }

    @Test @DisplayName("login warns when the vendor's environment variable will win over the saved key")
    void envWins() {
        env.put("XAI_API_KEY", "from-env");
        typed = "xai-saved-key-0000";
        assertThat(run("login", "grok")).isZero();
        assertThat(out()).contains("XAI_API_KEY is set");
    }

    @Test @DisplayName("an empty or spaced key is refused and nothing is saved")
    void badKeys() {
        typed = "   ";
        assertThat(run("login", "kimi")).isEqualTo(1);
        assertThat(err()).contains("no key given");
        typed = null;
        assertThat(run("login", "kimi")).isEqualTo(1);
        typed = "two words";
        assertThat(run("login", "kimi")).isEqualTo(1);
        assertThat(err()).contains("contains spaces");
        assertThat(SavedKeys.saved(KeyVendor.KIMI)).isNull();
    }

    @Test @DisplayName("an unknown vendor is a usage error that lists the known ones")
    void unknownVendor() {
        assertThat(run("login", "acme")).isEqualTo(2);
        assertThat(err()).contains("unknown vendor 'acme'").contains(KeyVendor.ids());
    }

    @Test @DisplayName("logout forgets the key, and says so when there was none")
    void logout() {
        SavedKeys.save(KeyVendor.MISTRAL, "m-key-12345678");
        assertThat(run("logout", "mistral")).isZero();
        assertThat(out()).contains("Forgot the saved Mistral AI key");
        assertThat(SavedKeys.saved(KeyVendor.MISTRAL)).isNull();

        assertThat(run("logout", "mistral")).isZero();
        assertThat(out()).contains("No Mistral AI key was saved");
    }

    @Test @DisplayName("status shows, per vendor, the environment, the saved key or nothing, never a whole key")
    void status() {
        SavedKeys.save(KeyVendor.NOVA, "nova-saved-key-9876");
        SavedKeys.save(KeyVendor.OPENAI, "sk-saved-but-shadowed-0000");
        env.put("OPENAI_API_KEY", "sk-from-env-5555");

        assertThat(run("status")).isZero();
        assertThat(out())
                .contains(home.resolve("credentials.json").toString())
                .containsPattern("openai\\s+from OPENAI_API_KEY\\s+\\*\\*\\*\\*5555")
                .containsPattern("nova\\s+saved by cafeai login\\s+\\*\\*\\*\\*9876")
                .containsPattern("kimi\\s+not set \\(cafeai login kimi\\)")
                .doesNotContain("sk-from-env").doesNotContain("nova-saved");
    }

    @Test @DisplayName("no arguments, or the wrong number, prints the usage and exits 2")
    void usage() {
        assertThat(run()).isEqualTo(2);
        assertThat(err()).contains("cafeai login <vendor>");
        assertThat(run("login")).isEqualTo(2);
        assertThat(run("help")).isZero();
        assertThat(out()).contains("Vendors: claude, openai");
    }

    // ── claude: a sign-in through Anthropic's ant CLI ───────────────────────────

    private final List<List<String>> ran = new ArrayList<>();

    private Path fakeAnt() throws IOException {
        Path ant = Files.createDirectories(home.resolve("bin")).resolve("ant.exe");
        Files.writeString(ant, "");
        env.put("CAFEAI_ANT", ant.toString());
        return ant;
    }

    /** What `ant auth login` leaves behind. */
    private void signedIn() throws IOException {
        Path anthropic = home.resolve("anthropic");
        Files.createDirectories(anthropic.resolve("configs"));
        Files.createDirectories(anthropic.resolve("credentials"));
        Files.writeString(anthropic.resolve("configs/default.json"),
                "{\"version\":\"1.0\",\"authentication\":{\"type\":\"user_oauth\",\"client_id\":\"cid\"}}");
        Files.writeString(anthropic.resolve("credentials/default.json"),
                "{\"type\":\"oauth_token\",\"access_token\":\"sk-ant-oat01-x\",\"expires_at\":"
                + Instant.now().plusSeconds(3600).getEpochSecond() + ",\"refresh_token\":\"r\","
                + "\"organization_name\":\"Acme\",\"account_email\":\"alice@acme.example\",\"workspace_name\":\"Engineering\"}");
    }

    @Test @DisplayName("login claude with no ant installed: how to install it, and nothing run")
    void claudeNoAnt() {
        env.put("PATH", home.resolve("empty").toString());
        assertThat(run("login", "claude")).isEqualTo(1);
        assertThat(err()).contains("brew install anthropics/tap/ant")
                .contains("https://github.com/anthropics/anthropic-cli/releases").contains("CAFEAI_ANT");
    }

    @Test @DisplayName("login claude refuses Apache Ant and never runs `ant auth login` with it")
    void claudeApacheAnt() throws IOException {
        fakeAnt();
        processes = (cmd, interactive, output) -> {
            ran.add(cmd);
            output.append("Apache Ant(TM) version 1.10.15 compiled on August 25 2024");
            return 0;
        };
        assertThat(run("login", "claude")).isEqualTo(1);
        assertThat(err()).contains("is Apache Ant, the Java build tool");
        assertThat(ran).extracting(c -> c.subList(1, c.size())).containsExactly(List.of("--version"));
    }

    @Test @DisplayName("login claude: checks the version, hands the terminal to `ant auth login`, then says who signed in")
    void claudeLogin() throws IOException {
        Path ant = fakeAnt();
        List<Boolean> interactive = new ArrayList<>();
        processes = (cmd, handover, output) -> {
            ran.add(cmd);
            interactive.add(handover);
            if (cmd.contains("--version")) {
                output.append("ant version 1.40.0\n");
                return 0;
            }
            try {
                signedIn();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return 0;
        };
        assertThat(run("login", "claude")).isZero();
        assertThat(ran).containsExactly(List.of(ant.toString(), "--version"), List.of(ant.toString(), "auth", "login"));
        assertThat(interactive).containsExactly(false, true);
        assertThat(out()).contains("Signed in to Claude: Acme, as alice@acme.example, workspace Engineering")
                .contains("profile 'default'");
    }

    @Test @DisplayName("login claude: a failed `ant auth login` is reported, not called a sign-in")
    void claudeLoginFails() throws IOException {
        fakeAnt();
        processes = (cmd, handover, output) -> {
            if (cmd.contains("--version")) {
                output.append("ant version 1.40.0");
                return 0;
            }
            return 3;
        };
        assertThat(run("login", "claude")).isEqualTo(1);
        assertThat(err()).contains("`ant auth login` failed (exit 3)");
    }

    @Test @DisplayName("status shows the Claude sign-in; ANTHROPIC_API_KEY, when set, is what apps use")
    void claudeStatus() throws IOException {
        assertThat(run("status")).isZero();
        assertThat(out()).containsPattern("claude\\s+not signed in \\(cafeai login claude\\)");

        signedIn();
        assertThat(run("status")).isZero();
        assertThat(out()).containsPattern("claude\\s+signed in: Acme, as alice@acme.example, workspace Engineering")
                .contains("token valid until").doesNotContain("sk-ant-oat01-x");

        env.put("ANTHROPIC_API_KEY", "sk-ant-api");
        assertThat(run("status")).isZero();
        assertThat(out()).containsPattern("claude\\s+from ANTHROPIC_API_KEY");
    }

    @Test @DisplayName("logout claude removes the sign-in from this machine, keeping the profile, as ant does")
    void claudeLogout() throws IOException {
        signedIn();
        assertThat(run("logout", "claude")).isZero();
        assertThat(out()).contains("Removed the Claude sign-in");
        assertThat(home.resolve("anthropic/credentials/default.json")).doesNotExist();
        assertThat(home.resolve("anthropic/configs/default.json")).exists();
        assertThat(run("logout", "claude")).isZero();
        assertThat(out()).contains("Not signed in to Claude");
    }

    @Test @DisplayName("ant is found on the PATH (ant.exe, or Apache's ant.bat on Windows)")
    void findsAntOnPath() throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        Path bin = Files.createDirectories(home.resolve("tools"));
        Path ant = bin.resolve(windows ? "ant.exe" : "ant");
        Files.writeString(ant, "");
        ant.toFile().setExecutable(true);
        env.put("PATH", home.resolve("nothing-here") + java.io.File.pathSeparator + bin);
        var claude = new ClaudeLogin(System.out, System.err, env::get, processes);
        assertThat(claude.findAnt()).contains(ant);
    }

    @Test @DisplayName("short keys are masked completely")
    void masking() {
        assertThat(CafeLogin.masked("abc")).isEqualTo("****");
        assertThat(CafeLogin.masked("sk-123456789")).isEqualTo("****6789");
    }
}
