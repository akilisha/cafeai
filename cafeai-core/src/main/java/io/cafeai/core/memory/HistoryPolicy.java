package io.cafeai.core.memory;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.config.AppConfig;
import io.cafeai.core.config.ConfigKey;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * What a session's stored history looks like to the model, turn by turn.
 *
 * <p>{@link MemoryStrategy} decides <em>where</em> a conversation is kept. A {@code HistoryPolicy}
 * decides <em>how much of it is sent</em>. Without one, every turn would resend the whole
 * conversation: the cost of each call would grow with the conversation until it overflowed the
 * model's context window and calls began to fail.
 *
 * <p>Three policies, each of which suits a different situation, and one to turn the limit off:
 *
 * <pre>{@code
 *   app.history(HistoryPolicy.lastMessages(20));          // the default: the newest 20 messages
 *   app.history(HistoryPolicy.tokenBudget(4000));         // as much recent history as fits in 4000 tokens
 *   app.history(HistoryPolicy.summarise()                 // older turns become a running summary
 *                   .keepRecent(6).after(20));
 *   app.history(HistoryPolicy.all());                     // send everything (the limit is yours to manage)
 * }</pre>
 *
 * <p>{@code lastMessages} and {@code tokenBudget} only choose what to send; the stored history stays
 * complete. {@code summarise} rewrites the stored history: it replaces the oldest messages with a
 * summary written by a model, which costs an extra model call on the turn that triggers it.
 *
 * <p>The policy applies to {@code app.prompt()}, {@code app.vision()} and {@code app.audio()} calls
 * that use {@code .session(...)}, streamed or not. It does not apply to agents, whose chat memory
 * is a window of the last {@code cafeai.agent.memory.window} messages.
 */
public interface HistoryPolicy {

    /**
     * The window {@link #lastMessages(int)} uses when no policy is set. {@code 0} or less sends the
     * whole history.
     *
     * <p>The {@code cafeai.memory.*} settings only supply numbers: which policy is in force is
     * decided in code with {@code app.history(...)}, and a setting never switches one on.
     */
    ConfigKey<Integer> WINDOW = ConfigKey.of(
        "cafeai.memory.window", Integer.class, 20,
        "Number of the newest messages of a session's history sent with each call. "
        + "0 sends the whole history.");

    /** The history budget {@link #tokenBudget()} uses when given no number, in estimated tokens. */
    ConfigKey<Integer> BUDGET = ConfigKey.of(
        "cafeai.memory.budget", Integer.class, 4000,
        "Estimated tokens of a session's history sent with each call, for HistoryPolicy.tokenBudget().");

    /** How many messages {@link #summarise()} lets a session reach before it writes a summary. */
    ConfigKey<Integer> SUMMARY_AFTER = ConfigKey.of(
        "cafeai.memory.summary.after", Integer.class, 20,
        "Messages a session may hold before HistoryPolicy.summarise() summarises the older ones.");

    /** How many of the newest messages {@link #summarise()} leaves as they are. */
    ConfigKey<Integer> SUMMARY_KEEP = ConfigKey.of(
        "cafeai.memory.summary.keep", Integer.class, 6,
        "Newest messages HistoryPolicy.summarise() keeps verbatim when it writes a summary.");

    /** The length asked of a summary, in words. */
    ConfigKey<Integer> SUMMARY_WORDS = ConfigKey.of(
        "cafeai.memory.summary.words", Integer.class, 200,
        "Length, in words, HistoryPolicy.summarise() asks the model to keep a summary under.");

    /** What to send for this turn: an optional summary of older turns, then messages in order. */
    History select(ConversationContext context);

    /**
     * Called after an exchange is stored, and may rewrite the stored history. Return {@code true}
     * if it did, so the caller stores the result. The default keeps history as it is.
     *
     * @param summariser turns older messages into a summary, using the model this policy names
     *                   (see {@link #summaryModel()}) or, if none, the model that answered
     */
    default boolean compact(ConversationContext context, Summariser summariser) {
        return false;
    }

    /** The model that writes summaries, or {@code null} to use the model that answered the call. */
    default AiProvider summaryModel() {
        return null;
    }

    /** A summary (may be {@code null}) and the messages that follow it. */
    record History(String summary, List<ConversationContext.Message> messages) {}

    /** Writes a summary of {@code older}, continuing {@code previousSummary} if there is one. */
    @FunctionalInterface
    interface Summariser {
        String summarise(String previousSummary, List<ConversationContext.Message> older);
    }

    // -- Factories ---------------------------------------------------------------------------------

    /**
     * The policy in force when the application sets none: {@link #lastMessages(int)} with
     * {@code cafeai.memory.window} (default 20), or {@link #all()} if that is 0 or less.
     */
    static HistoryPolicy configured() {
        return HistoryPolicies.defaultPolicy();
    }

    /** Sends the whole history every turn. */
    static HistoryPolicy all() {
        return HistoryPolicies.ALL;
    }

    /**
     * Sends the newest {@code count} messages (a message is one user turn or one assistant reply).
     * If the window would begin with an assistant reply, that reply is left out so the history
     * starts with a user turn.
     *
     * @throws IllegalArgumentException if {@code count} is less than 2, which could not hold one exchange
     */
    static HistoryPolicy lastMessages(int count) {
        return new HistoryPolicies.LastMessages(count);
    }

    /** {@link #tokenBudget(int)} with the budget from {@code cafeai.memory.budget} (default 4000). */
    static HistoryPolicy tokenBudget() {
        return tokenBudget(AppConfig.load().get(BUDGET));
    }

    /**
     * Sends as many of the newest messages as fit in {@code maxTokens}, estimating a token as about
     * four characters. The most recent exchange is always sent, even if it alone is over budget.
     * The budget covers the history only, not the system prompt, retrieved documents or the new message.
     *
     * @throws IllegalArgumentException if {@code maxTokens} is not positive
     */
    static HistoryPolicy tokenBudget(int maxTokens) {
        return tokenBudget(maxTokens, HistoryPolicies.DEFAULT_ESTIMATE);
    }

    /**
     * As {@link #tokenBudget(int)}, with your own token count for a piece of text, such as a
     * LangChain4j {@code TokenCountEstimator} for the model in use: {@code text -> estimator.estimateTokenCountInText(text)}.
     */
    static HistoryPolicy tokenBudget(int maxTokens, ToIntFunction<String> countTokens) {
        return new HistoryPolicies.TokenBudget(maxTokens, countTokens);
    }

    /**
     * Keeps the newest messages as they are and folds everything older into a running summary that
     * is sent as part of the system prompt. The thresholds default to {@code cafeai.memory.summary.after}
     * (20) and {@code cafeai.memory.summary.keep} (6). Tune it with {@link Summarise#keepRecent(int)},
     * {@link Summarise#after(int)} and {@link Summarise#model(AiProvider)}.
     */
    static Summarise summarise() {
        return new HistoryPolicies.SummarisePolicy();
    }

    /**
     * The summarising policy. It summarises once a session holds more than {@link #after(int)}
     * messages, leaving the newest {@link #keepRecent(int)} untouched; after that the history is
     * the summary plus those messages, growing again until the next summary.
     *
     * <p>The summary is only as good as the model that writes it. A very small model (a 0.5B one run
     * in-process, say) may answer the request conversationally instead of summarising; name a stronger
     * one for the job with {@link #model(AiProvider)}.
     *
     * <p>If the summarising call fails, the history is left as it is and the call that triggered
     * it still succeeds; the next turn tries again. In the meantime no more than {@code after}
     * messages are sent.
     */
    interface Summarise extends HistoryPolicy {

        /** Messages kept verbatim after a summary is written. Default 6, at least 2. */
        Summarise keepRecent(int messages);

        /** A summary is written once the session holds more than this many messages. Default 20. */
        Summarise after(int messages);

        /** The model that writes summaries, for example a cheaper one. Default: the model that answered. */
        Summarise model(AiProvider provider);
    }
}
