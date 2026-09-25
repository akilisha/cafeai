package io.cafeai.core.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionStoreTest {

    // -- SessionStore.inMemory() -------------------------------------------------

    @Test
    void inMemoryRoundTrips() {
        SessionStore store = SessionStore.inMemory();
        Session session = store.create();
        session.set("userId", "alex");
        store.save(session);

        Session loaded = store.load(session.id());

        assertThat(loaded).isNotNull();
        assertThat(loaded.get("userId")).isEqualTo("alex");
        assertThat(store.exists(session.id())).isTrue();
    }

    @Test
    void inMemoryLoadOfUnknownIdReturnsNull() {
        assertThat(SessionStore.inMemory().load("nope")).isNull();
    }

    @Test
    void inMemoryDestroyOfUnknownIdIsNoOp() {
        SessionStore store = SessionStore.inMemory();
        store.destroy("nope");
        assertThat(store.exists("nope")).isFalse();
    }

    @Test
    void inMemoryDestroyRemovesTheSession() {
        SessionStore store = SessionStore.inMemory();
        Session session = store.create();
        store.save(session);

        store.destroy(session.id());

        assertThat(store.exists(session.id())).isFalse();
        assertThat(store.load(session.id())).isNull();
    }

    // -- SessionStore.sqlite() without cafeai-session on the classpath ----------

    @Test
    void sqliteWithoutModuleThrowsWithDependencyCoordinates() {
        assertThatThrownBy(SessionStore::sqlite)
            .isInstanceOf(SessionStore.SessionModuleNotFoundException.class)
            .hasMessageContaining("cafeai-session");
    }

    // -- Session ------------------------------------------------------------------

    @Test
    void sessionGetSetRemove() {
        Session session = new Session("id-1");

        session.set("a", 1);
        assertThat(session.get("a")).isEqualTo(1);
        assertThat(session.get("a", Integer.class)).isEqualTo(1);

        session.remove("a");
        assertThat(session.get("a")).isNull();
    }

    @Test
    void sessionAttributesIsAnUnmodifiableSnapshot() {
        Session session = new Session("id-1");
        session.set("a", 1);

        assertThatThrownBy(() -> session.attributes().put("b", 2))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sessionIsNewOnlyBeforeAnyAttributeIsSet() {
        Session session = new Session("id-1");
        assertThat(session.isNew()).isTrue();

        session.set("a", 1);
        assertThat(session.isNew()).isFalse();
    }

    @Test
    void sessionInvalidateSetsFlagAndRunsBoundHook() {
        Session session = new Session("id-1");
        var hookRan = new boolean[1];
        session.bindInvalidationHook(() -> hookRan[0] = true);

        assertThat(session.isInvalidated()).isFalse();
        session.invalidate();

        assertThat(session.isInvalidated()).isTrue();
        assertThat(hookRan[0]).isTrue();
    }
}
