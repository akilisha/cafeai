package io.cafeai.session;

import io.cafeai.core.session.Session;
import io.cafeai.core.spi.SessionStoreProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteSessionStoreTest {

    @Test
    void roundTrips(@TempDir Path dir) {
        var store = new SqliteSessionStore(dir.resolve("sessions.db"));

        Session session = store.create();
        session.set("userId", "alex");
        store.save(session);

        Session loaded = store.load(session.id());

        assertThat(loaded).isNotNull();
        assertThat(loaded.get("userId")).isEqualTo("alex");
        assertThat(store.exists(session.id())).isTrue();

        store.destroy(session.id());
        assertThat(store.exists(session.id())).isFalse();
        assertThat(store.load(session.id())).isNull();

        store.close();
    }

    @Test
    void loadOfUnknownIdReturnsNull(@TempDir Path dir) {
        var store = new SqliteSessionStore(dir.resolve("sessions.db"));
        assertThat(store.load("nope")).isNull();
        store.close();
    }

    @Test
    void survivesReopeningAtTheSameFile(@TempDir Path dir) {
        Path dbFile = dir.resolve("sessions.db");

        var first = new SqliteSessionStore(dbFile);
        Session session = first.create();
        session.set("cart", "3-items");
        first.save(session);
        first.close();

        var reopened = new SqliteSessionStore(dbFile);
        Session recovered = reopened.load(session.id());

        assertThat(recovered).isNotNull();
        assertThat(recovered.get("cart")).isEqualTo("3-items");
        reopened.close();
    }

    @Test
    void concurrentSaveAndLoadOfDistinctSessions(@TempDir Path dir) throws Exception {
        var store = new SqliteSessionStore(dir.resolve("sessions.db"));
        int threads = 8;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    Session session = store.create();
                    session.set("idx", idx);
                    store.save(session);
                    Session loaded = store.load(session.id());
                    assertThat(loaded.get("idx")).isEqualTo(idx);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).as("all threads completed").isTrue();
        pool.shutdown();
        store.close();
    }

    @Test
    void sessionStoreProviderIsDiscoverableViaServiceLoader() {
        SessionStoreProvider provider = ServiceLoader.load(SessionStoreProvider.class)
            .findFirst().orElseThrow();

        assertThat(provider).isInstanceOf(CafeAISessionProvider.class);
    }
}
