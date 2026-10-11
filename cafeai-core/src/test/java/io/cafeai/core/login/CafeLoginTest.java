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
import java.nio.file.Path;
import java.util.HashMap;
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

    @BeforeEach
    void useTempDir() {
        before = System.getProperty("cafeai.config.dir");
        System.setProperty("cafeai.config.dir", home.toString());
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
                p -> { prompt = p; return typed; }, env::get);
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
        assertThat(out()).contains("Vendors: openai");
    }

    @Test @DisplayName("short keys are masked completely")
    void masking() {
        assertThat(CafeLogin.masked("abc")).isEqualTo("****");
        assertThat(CafeLogin.masked("sk-123456789")).isEqualTo("****6789");
    }
}
