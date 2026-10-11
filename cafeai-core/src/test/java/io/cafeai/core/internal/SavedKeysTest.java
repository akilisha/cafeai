package io.cafeai.core.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.cafeai.core.ai.Credentials;
import io.cafeai.core.ai.Grok;
import io.cafeai.core.ai.OpenAI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("SavedKeys: the keys cafeai login saves, and the order a key is looked up in")
class SavedKeysTest {

    @TempDir Path home;
    private String before;

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

    @Test @DisplayName("save, read back, replace and remove; the file holds a version and the keys")
    void roundTrip() throws Exception {
        SavedKeys.save(KeyVendor.DEEPSEEK, "sk-deep-1");
        SavedKeys.save(KeyVendor.KIMI, "sk-kimi-1");
        assertThat(SavedKeys.saved(KeyVendor.DEEPSEEK)).isEqualTo("sk-deep-1");

        SavedKeys.save(KeyVendor.DEEPSEEK, "sk-deep-2");
        assertThat(SavedKeys.saved(KeyVendor.DEEPSEEK)).isEqualTo("sk-deep-2");

        var file = new ObjectMapper().readTree(home.resolve("credentials.json").toFile());
        assertThat(file.get("version").asText()).isEqualTo("1.0");
        assertThat(file.get("keys").get("kimi").asText()).isEqualTo("sk-kimi-1");

        assertThat(SavedKeys.remove(KeyVendor.DEEPSEEK)).isTrue();
        assertThat(SavedKeys.remove(KeyVendor.DEEPSEEK)).isFalse();
        assertThat(SavedKeys.saved(KeyVendor.DEEPSEEK)).isNull();
        assertThat(SavedKeys.saved(KeyVendor.KIMI)).isEqualTo("sk-kimi-1");
        try (var leftovers = Files.list(home)) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).containsExactly("credentials.json");
        }
    }

    @Test @DisplayName("a change made by another process is picked up on the next lookup")
    void seesOutsideChanges() throws Exception {
        SavedKeys.save(KeyVendor.KIMI, "first");
        assertThat(SavedKeys.saved(KeyVendor.KIMI)).isEqualTo("first");
        Path file = home.resolve("credentials.json");
        Files.writeString(file, "{\"version\":\"1.0\",\"keys\":{\"kimi\":\"second\"}}");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(SavedKeys.saved(KeyVendor.KIMI)).isEqualTo("second");
    }

    @Test @DisplayName("on POSIX systems the file and its directory are the owner's only")
    void ownerOnly() throws Exception {
        assumeTrue(home.getFileSystem().supportedFileAttributeViews().contains("posix"), "not a POSIX file system");
        Path dir = home.resolve("nested");
        System.setProperty("cafeai.config.dir", dir.toString());
        SavedKeys.save(KeyVendor.KIMI, "k");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("credentials.json"))))
                .isEqualTo("rw-------");
    }

    @Test @DisplayName("the environment variable wins over the saved key; the saved key over nothing")
    void order() {
        SavedKeys.save(KeyVendor.DEEPSEEK, "saved-key");
        Map<String, String> env = Map.of("DEEPSEEK_API_KEY", "env-key");

        assertThat(SavedKeys.resolve(KeyVendor.DEEPSEEK, env::get))
                .isEqualTo(new SavedKeys.Resolved("env-key", SavedKeys.Source.ENVIRONMENT));
        assertThat(SavedKeys.resolve(KeyVendor.DEEPSEEK, name -> null))
                .isEqualTo(new SavedKeys.Resolved("saved-key", SavedKeys.Source.SAVED));
        assertThat(SavedKeys.resolve(KeyVendor.DEEPSEEK, name -> "  "))
                .as("a blank variable doesn't count").extracting(SavedKeys.Resolved::source)
                .isEqualTo(SavedKeys.Source.SAVED);
        assertThat(SavedKeys.resolve(KeyVendor.KIMI, name -> null).source()).isEqualTo(SavedKeys.Source.NONE);
    }

    @Test @DisplayName("with no key anywhere, the message names both ways to provide one")
    void missing() {
        assertThatThrownBy(() -> SavedKeys.require(KeyVendor.KIMI, "kimi", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cafeai login kimi")
                .hasMessageContaining("MOONSHOT_API_KEY")
                .hasMessageContaining(KeyVendor.KIMI.keysPage());
    }

    @Test @DisplayName("a broken keys file is reported with its path, not ignored")
    void broken() throws Exception {
        Files.writeString(home.resolve("credentials.json"), "{not json");
        assertThatThrownBy(() -> SavedKeys.saved(KeyVendor.KIMI))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(home.resolve("credentials.json").toString());
    }

    @Test @DisplayName("the test JVM's placeholder environment key wins over a saved Grok key")
    void providerUsesTheEnvironmentFirst() {
        SavedKeys.save(KeyVendor.GROK, "saved-grok-key");
        // build.gradle sets XAI_API_KEY=test-key-not-real for the test JVM.
        assertThat(SavedKeys.resolve(KeyVendor.GROK).key()).isEqualTo("test-key-not-real");
        assertThat(Grok.of("grok-4.7").toChatModel()).isNotNull();
    }

    @Test @DisplayName("Credentials.saved sends the saved key on each call, and sees a key saved later")
    void credentialsSaved() throws Exception {
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"m\",\"choices\":[{\"index\":0,"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var provider = OpenAI.of("deepseek-chat")
                    .withBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .withCredentials(Credentials.saved("deepseek"));
            var model = LangchainBridge.INSTANCE.modelFor(provider);

            assertThatThrownBy(() -> model.chat("hi")).hasStackTraceContaining("cafeai login deepseek");
            assertThat(authorizations).as("nothing sent without a key").isEmpty();

            SavedKeys.save(KeyVendor.DEEPSEEK, "sk-deep-saved");
            assertThat(model.chat("hi")).isEqualTo("ok");
            assertThat(authorizations).containsExactly("Bearer sk-deep-saved");
        } finally {
            server.stop(0);
        }
    }

    @Test @DisplayName("Credentials.saved refuses a vendor it doesn't know, naming the known ones")
    void unknownVendor() {
        assertThatThrownBy(() -> Credentials.saved("acme"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("openai, grok, mistral, nova, kimi, deepseek");
    }
}
