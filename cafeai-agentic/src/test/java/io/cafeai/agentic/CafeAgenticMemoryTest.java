package io.cafeai.agentic;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import io.cafeai.core.Attributes;
import io.cafeai.core.CafeAI;
import io.cafeai.core.RequestScope;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.memory.ConversationKeys;
import io.cafeai.core.memory.MemoryStrategy;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CafeAgenticMemoryTest {

    @Test
    void of_roundTripsThroughTheMemoryStrategy() {
        MemoryStrategy strategy = MemoryStrategy.inMemory();
        ChatMemoryProvider provider = CafeAgenticMemory.of(strategy);

        ChatMemory memory = provider.get("session-1");
        memory.add(UserMessage.from("hello"));

        var ctx = strategy.retrieve("session-1");
        assertThat(ctx).isNotNull();
        assertThat(ctx.messages()).extracting(m -> m.content()).contains("hello");
    }

    @Test
    void of_keysMemoryToTheCaller_alsoOnACarryingExecutor() throws Exception {
        MemoryStrategy strategy = MemoryStrategy.inMemory();
        ChatMemoryProvider provider = CafeAgenticMemory.of(strategy);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Identity alice = Identity.builder("https://issuer.example.com", "alice")
            .expiresAt(Instant.now().plusSeconds(3600)).build();

        var app = CafeAI.create();
        app.filter((req, res, next) -> {
            req.setAttribute(Attributes.IDENTITY, alice);
            next.run();
        });
        app.get("/", (req, res, next) -> {
            try {
                provider.get("m-1").add(UserMessage.from("on the request thread"));
                // How a parallel agentic workflow runs its agents, given RequestScope.carrying(...):
                RequestScope.carrying(pool).execute(() ->
                    provider.get("m-1").add(UserMessage.from("on the executor")));
                pool.submit(() -> { }).get();
                res.send("ok");
            } catch (Exception e) {
                res.status(500).send(e.toString());
            }
        });
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        try {
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/")).build(),
                HttpResponse.BodyHandlers.ofString());

            assertThat(strategy.retrieve("m-1")).isNull();   // never under the bare id
            var ctx = strategy.retrieve(ConversationKeys.forCaller(alice, "m-1"));
            assertThat(ctx.messages()).extracting(m -> m.content())
                .contains("on the request thread", "on the executor");
        } finally {
            app.stop();
            pool.shutdownNow();
        }
    }

    @Test
    void of_keepsMemoryIdsSeparate() {
        MemoryStrategy strategy = MemoryStrategy.inMemory();
        ChatMemoryProvider provider = CafeAgenticMemory.of(strategy);

        provider.get("a").add(UserMessage.from("from-a"));
        provider.get("b").add(UserMessage.from("from-b"));

        assertThat(strategy.retrieve("a").messages()).extracting(m -> m.content()).contains("from-a");
        assertThat(strategy.retrieve("a").messages()).extracting(m -> m.content()).doesNotContain("from-b");
        assertThat(strategy.retrieve("b").messages()).extracting(m -> m.content()).contains("from-b");
    }
}
