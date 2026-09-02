package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Jlama;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live end-to-end check: a real Jlama model streaming real tokens through
 * {@code app.prompt(...).stream()}.
 *
 * <p>{@code @Disabled} by default — it pulls a ~400 MB model from Hugging Face
 * on first run. Run it by hand:
 * <pre>
 *   ./gradlew :cafeai-examples:test --tests '*JlamaStreamingLiveTest'
 * </pre>
 * (the {@code test} task already passes {@code --add-modules jdk.incubator.vector}).
 */
class JlamaStreamingLiveTest {

    @Test
    @Disabled("pulls a ~400 MB model; run manually")
    void streamsTokensFromRealJlamaModel() throws Exception {
        var app = CafeAI.create();
        app.ai(Jlama.qwen2());   // ~0.5B params

        var assembled = new StringBuilder();
        var error     = new AtomicReference<Throwable>();
        var done      = new CountDownLatch(1);

        app.prompt("Reply with exactly: hello world")
           .stream()
           .subscribe(new Flow.Subscriber<String>() {
               public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
               public void onNext(String token) { assembled.append(token); }
               public void onError(Throwable t) { error.set(t); done.countDown(); }
               public void onComplete() { done.countDown(); }
           });

        assertThat(done.await(180, TimeUnit.SECONDS)).as("inference timed out").isTrue();
        assertThat(error.get()).isNull();
        assertThat(assembled.toString()).isNotBlank();
    }
}
