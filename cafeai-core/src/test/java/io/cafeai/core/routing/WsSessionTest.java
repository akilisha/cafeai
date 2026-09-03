package io.cafeai.core.routing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WsSessionTest {

    /** Records everything sent, and can simulate a client that disconnects. */
    static final class RecordingSession implements WsSession {
        final List<String> sent = new ArrayList<>();
        volatile boolean open = true;

        @Override public void send(String message) { sent.add(message); }
        @Override public void send(byte[] data) {}
        @Override public void close(int code, String reason) { open = false; }
        @Override public String id() { return "test"; }
        @Override public boolean isOpen() { return open; }
    }

    /** A synchronous publisher — delivers all items on the subscribing thread, then completes. */
    private static Flow.Publisher<String> sync(Consumer<Flow.Subscriber<? super String>> body) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {}
                @Override public void cancel() {}
            });
            body.accept(subscriber);
        };
    }

    private static Flow.Publisher<String> publisherOf(String... tokens) {
        return sync(sub -> {
            for (String t : tokens) sub.onNext(t);
            sub.onComplete();
        });
    }

    @Test
    void streamTokens_sendsEachTokenThenTheSentinel() {
        var session = new RecordingSession();

        session.streamTokens(publisherOf("Hel", "lo", " world"));

        assertThat(session.sent).containsExactly("Hel", "lo", " world", "[DONE]");
    }

    @Test
    void streamTokens_customSentinel_andNullMeansNone() {
        var a = new RecordingSession();
        a.streamTokens(publisherOf("x", "y"), "<<end>>");
        assertThat(a.sent).containsExactly("x", "y", "<<end>>");

        var b = new RecordingSession();
        b.streamTokens(publisherOf("x", "y"), null);
        assertThat(b.sent).containsExactly("x", "y");
    }

    @Test
    void streamTokens_stopsSendingOnceTheClientDisconnects() {
        var session = new RecordingSession();

        session.streamTokens(sync(sub -> {
            sub.onNext("first");
            session.open = false;   // client goes away
            sub.onNext("second");
            sub.onNext("third");
            sub.onComplete();
        }));

        assertThat(session.sent).containsExactly("first");
    }
}
