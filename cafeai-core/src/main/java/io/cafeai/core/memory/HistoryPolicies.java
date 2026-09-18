package io.cafeai.core.memory;

import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.config.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** The implementations behind {@link HistoryPolicy}'s factories. */
final class HistoryPolicies {

    private HistoryPolicies() {}

    /** About four characters a token, plus a few for the role and framing of each message. */
    static final ToIntFunction<String> DEFAULT_ESTIMATE = text -> (text.length() + 3) / 4 + 4;

    static final HistoryPolicy ALL = context -> new HistoryPolicy.History(context.summary(), context.messages());

    /** The policy in force when the application sets none: {@code cafeai.memory.window} messages. */
    static HistoryPolicy defaultPolicy() {
        int window = AppConfig.load().get(HistoryPolicy.WINDOW);
        return window <= 0 ? ALL : new LastMessages(window);
    }

    /** {@code messages} with a leading assistant reply dropped, so the history starts with a user turn. */
    private static List<ConversationContext.Message> startingWithUser(List<ConversationContext.Message> messages) {
        int from = 0;
        while (from < messages.size() && !"user".equalsIgnoreCase(messages.get(from).role())) {
            from++;
        }
        return from == 0 ? messages : messages.subList(from, messages.size());
    }

    private static List<ConversationContext.Message> newest(List<ConversationContext.Message> all, int count) {
        return all.size() <= count ? all : all.subList(all.size() - count, all.size());
    }

    // -- lastMessages ------------------------------------------------------------------------------

    static final class LastMessages implements HistoryPolicy {
        private final int count;

        LastMessages(int count) {
            if (count < 2) {
                throw new IllegalArgumentException(
                    "lastMessages(" + count + ") cannot hold one exchange; use 2 or more, or HistoryPolicy.all()");
            }
            this.count = count;
        }

        @Override
        public History select(ConversationContext context) {
            return new History(context.summary(), startingWithUser(newest(context.messages(), count)));
        }
    }

    // -- tokenBudget -------------------------------------------------------------------------------

    static final class TokenBudget implements HistoryPolicy {
        private final int maxTokens;
        private final ToIntFunction<String> count;

        TokenBudget(int maxTokens, ToIntFunction<String> count) {
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("tokenBudget must be positive, was " + maxTokens);
            }
            this.maxTokens = maxTokens;
            this.count = Objects.requireNonNull(count, "countTokens must not be null");
        }

        @Override
        public History select(ConversationContext context) {
            List<ConversationContext.Message> all = context.messages();
            int used = 0;
            int keepFrom = all.size();
            for (int i = all.size() - 1; i >= 0; i--) {
                used += count.applyAsInt(all.get(i).content());
                if (used > maxTokens) break;
                keepFrom = i;
            }
            // The latest exchange is always sent, whatever it costs.
            keepFrom = Math.min(keepFrom, Math.max(0, all.size() - 2));
            return new History(context.summary(), startingWithUser(all.subList(keepFrom, all.size())));
        }
    }

    // -- summarise ---------------------------------------------------------------------------------

    static final class SummarisePolicy implements HistoryPolicy.Summarise {
        private int keepRecent = AppConfig.load().get(HistoryPolicy.SUMMARY_KEEP);
        private int after = AppConfig.load().get(HistoryPolicy.SUMMARY_AFTER);
        private AiProvider model;

        @Override public HistoryPolicy.Summarise keepRecent(int messages) {
            if (messages < 2) throw new IllegalArgumentException("keepRecent must be at least 2, was " + messages);
            this.keepRecent = messages;
            return this;
        }

        @Override public HistoryPolicy.Summarise after(int messages) {
            if (messages < 3) throw new IllegalArgumentException("after must be at least 3, was " + messages);
            this.after = messages;
            return this;
        }

        @Override public HistoryPolicy.Summarise model(AiProvider provider) {
            this.model = Objects.requireNonNull(provider, "provider must not be null");
            return this;
        }

        @Override public AiProvider summaryModel() { return model; }

        private void checkSettings() {
            if (after <= keepRecent) {
                throw new IllegalStateException(
                    "summarise().after(" + after + ") must be greater than keepRecent(" + keepRecent + ")");
            }
        }

        @Override
        public History select(ConversationContext context) {
            checkSettings();
            // If summarising has been failing, still send no more than 'after' messages.
            return new History(context.summary(), startingWithUser(newest(context.messages(), after)));
        }

        @Override
        public boolean compact(ConversationContext context, Summariser summariser) {
            checkSettings();
            List<ConversationContext.Message> all = context.messages();
            if (all.size() <= after) return false;

            int older = all.size() - keepRecent;
            // What is kept must begin with a user turn, so an assistant reply at the boundary goes into the summary.
            while (older < all.size() && !"user".equalsIgnoreCase(all.get(older).role())) {
                older++;
            }
            if (older == 0 || older >= all.size()) return false;

            String summary = summariser.summarise(context.summary(), new ArrayList<>(all.subList(0, older)));
            if (summary == null || summary.isBlank()) return false;
            context.compact(older, summary.strip());
            return true;
        }
    }
}
