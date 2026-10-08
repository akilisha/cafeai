package io.cafeai.core.identity;

import io.cafeai.core.CafeAI;
import io.cafeai.core.rag.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("An app serving verified callers needs a vector store that enforces access, or public documents")
class VectorStoreAccessTest {

    private CafeAI app;

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        IdentityMode.resetForTests();
    }

    private void start(VectorStore store) throws Exception {
        app = CafeAI.create();
        app.vectordb(store);
        var started = new CountDownLatch(1);
        app.listen(0, started::countDown);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test @DisplayName("refused: a store that can't tell callers apart")
    void unenforcedRefused() {
        IdentityMode.enable();
        assertThat(VectorStore.inMemory().access()).isEqualTo(VectorStore.Access.UNENFORCED);
        assertThatThrownBy(() -> start(VectorStore.inMemory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("everyoneMayRead").hasMessageContaining("rowLevelSecurity");
        app = null;   // never started
    }

    @Test @DisplayName("allowed: the same store, its documents declared public")
    void publicAllowed() throws Exception {
        IdentityMode.enable();
        var store = VectorStore.everyoneMayRead(VectorStore.inMemory());
        assertThat(store.access()).isEqualTo(VectorStore.Access.PUBLIC);
        start(store);
        assertThat(app.port()).isPositive();
    }

    @Test @DisplayName("allowed: any store, in an app without verified callers")
    void withoutIdentity() throws Exception {
        start(VectorStore.inMemory());
        assertThat(app.port()).isPositive();
    }

    @Test @DisplayName("everyoneMayRead passes everything through to the store it wraps")
    void delegates() {
        var inner = VectorStore.inMemory();
        var store = VectorStore.everyoneMayRead(inner);
        store.upsert("a", "text", new float[]{1f, 0f}, "src", 0);
        assertThat(inner.count()).isEqualTo(1);
        assertThat(store.exists("a")).isTrue();
        assertThat(store.search(new float[]{1f, 0f}, 1)).hasSize(1);
        store.deleteBySource("src");
        assertThat(store.count()).isZero();
    }
}
