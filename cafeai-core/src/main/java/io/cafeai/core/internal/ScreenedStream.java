package io.cafeai.core.internal;

import io.cafeai.core.config.ConfigKey;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Screened streaming: a streamed answer is held back until a sentence ends, the answer
 * so far is screened by the output guardrails, and only then is the sentence released.
 * A blocked answer stops there -- the client has the sentences that passed, then the
 * refusal -- so flagged text never leaves the server, and nothing has to be taken back.
 *
 * <p>Used only when the app has output guardrails; the cost is one sentence of latency,
 * and the guardrails run once per released sentence, over the whole answer so far (so a
 * phrase split across sentences is still seen).
 */
final class ScreenedStream {

    /** {@code sentence} (the default): screen each sentence before it is sent; {@code off}: send tokens as they arrive. */
    static final ConfigKey<String> MODE = ConfigKey.of(
        "cafeai.stream.screen", String.class, "sentence",
        "With output guardrails, a streamed answer is screened sentence by sentence before it is sent (sentence), "
        + "or sent token by token and screened only at the end (off).");

    /** Text with no sentence end yet is released at a word break once this much is held. */
    static final int MAX_HELD = 400;

    /** What the output guardrails made of the answer so far: the same text, or a refusal. */
    record Result(String text, boolean flagged) { }

    private final Function<String, Result> screen;
    private final Consumer<String> emit;
    private final StringBuilder released = new StringBuilder();
    private final StringBuilder held = new StringBuilder();
    private String refusal;
    private boolean flagged;

    ScreenedStream(Function<String, Result> screen, Consumer<String> emit) {
        this.screen = screen;
        this.emit = emit;
    }

    /** A token from the model. After a block, the rest of the model's answer is dropped. */
    void accept(String token) {
        if (refusal != null || token == null) return;
        held.append(token);
        int cut = releasePoint(held);
        if (cut > 0) release(cut);
    }

    /** The model has finished: screen and release what is left. Returns the answer as screened. */
    String finish() {
        if (refusal == null && !held.isEmpty()) release(held.length());
        return refusal != null ? refusal : released.toString();
    }

    /** An output guardrail blocked the answer. */
    boolean blocked() {
        return refusal != null;
    }

    /** An output guardrail flagged the answer (blocked it, or only warned). */
    boolean flagged() {
        return flagged;
    }

    private void release(int cut) {
        String chunk = held.substring(0, cut);
        String candidate = released + chunk;
        Result result = screen.apply(candidate);
        flagged |= result.flagged();
        if (!candidate.equals(result.text())) {
            refusal = result.text();
            held.setLength(0);
            emit.accept(refusal);
            return;
        }
        released.append(chunk);
        held.delete(0, cut);
        emit.accept(chunk);
    }

    /**
     * Where held text can be released: after the last sentence end (., ! or ? followed by
     * whitespace) or line break; failing that, at the last word break once {@link #MAX_HELD}
     * characters are held; otherwise 0, keep holding.
     */
    static int releasePoint(CharSequence s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '\n') return i + 1;
            if (Character.isWhitespace(c) && i > 0 && ".!?".indexOf(s.charAt(i - 1)) >= 0) return i + 1;
        }
        if (s.length() >= MAX_HELD) {
            for (int i = s.length() - 1; i > 0; i--) {
                if (Character.isWhitespace(s.charAt(i))) return i + 1;
            }
            return s.length();
        }
        return 0;
    }
}
