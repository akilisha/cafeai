package io.cafeai.core.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What each {@link HistoryPolicy} selects and rewrites, with no model and no engine. */
@DisplayName("history policies")
class HistoryPolicyTest {

    /** A session of {@code exchanges} user/assistant pairs: "u0", "a0", "u1", "a1", ... */
    private static ConversationContext session(int exchanges) {
        var ctx = new ConversationContext("s");
        for (int i = 0; i < exchanges; i++) {
            ctx.addMessage("user", "u" + i);
            ctx.addMessage("assistant", "a" + i);
        }
        return ctx;
    }

    private static List<String> contents(HistoryPolicy.History h) {
        return h.messages().stream().map(ConversationContext.Message::content).toList();
    }

    // -- all ------------------------------------------------------------------------------------------

    @Test @DisplayName("all() sends everything")
    void all() {
        assertThat(HistoryPolicy.all().select(session(30)).messages()).hasSize(60);
    }

    // -- lastMessages ---------------------------------------------------------------------------------

    @Test @DisplayName("lastMessages(n) sends the newest n, in order")
    void lastMessages() {
        var h = HistoryPolicy.lastMessages(4).select(session(5));

        assertThat(contents(h)).containsExactly("u3", "a3", "u4", "a4");
    }

    @Test @DisplayName("a window that would begin with an assistant reply drops it, so history starts with a user turn")
    void startsWithUser() {
        var h = HistoryPolicy.lastMessages(3).select(session(5));

        assertThat(contents(h)).containsExactly("u4", "a4");
    }

    @Test @DisplayName("a session shorter than the window is sent whole")
    void shorterThanWindow() {
        assertThat(HistoryPolicy.lastMessages(20).select(session(3)).messages()).hasSize(6);
    }

    @Test @DisplayName("a window too small for one exchange is refused")
    void windowTooSmall() {
        assertThatThrownBy(() -> HistoryPolicy.lastMessages(1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lastMessages(1)");
    }

    @Test @DisplayName("the default policy is the newest 20 messages")
    void configuredDefault() {
        assertThat(HistoryPolicy.configured().select(session(30)).messages()).hasSize(20);
    }

    @Test @DisplayName("the summary of a session travels with whatever the window keeps")
    void summaryTravels() {
        var ctx = session(5);
        ctx.compact(2, "earlier facts");

        assertThat(HistoryPolicy.lastMessages(4).select(ctx).summary()).isEqualTo("earlier facts");
    }

    // -- tokenBudget ----------------------------------------------------------------------------------

    @Test @DisplayName("tokenBudget keeps the newest messages that fit, using the counter it is given")
    void tokenBudgetFits() {
        // every message costs 10 tokens; a budget of 45 holds four of them
        var h = HistoryPolicy.tokenBudget(45, text -> 10).select(session(5));

        assertThat(contents(h)).containsExactly("u3", "a3", "u4", "a4");
    }

    @Test @DisplayName("tokenBudget always sends the latest exchange, even when it alone is over budget")
    void tokenBudgetKeepsLatestExchange() {
        var h = HistoryPolicy.tokenBudget(1, text -> 1000).select(session(5));

        assertThat(contents(h)).containsExactly("u4", "a4");
    }

    @Test @DisplayName("tokenBudget with the default estimate counts about four characters a token")
    void defaultEstimate() {
        var ctx = new ConversationContext("s");
        for (int i = 0; i < 10; i++) {
            ctx.addMessage("user", "x".repeat(400));       // ~104 tokens each
            ctx.addMessage("assistant", "y".repeat(400));
        }

        int sent = HistoryPolicy.tokenBudget(500).select(ctx).messages().size();

        assertThat(sent).isEqualTo(4);   // four messages ~416 tokens; a fifth would pass 500
    }

    @Test @DisplayName("a budget that is not positive is refused")
    void budgetNotPositive() {
        assertThatThrownBy(() -> HistoryPolicy.tokenBudget(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // -- summarise ------------------------------------------------------------------------------------

    private static HistoryPolicy.Summariser summariser(List<List<String>> seen) {
        return (previous, older) -> {
            seen.add(older.stream().map(ConversationContext.Message::content).toList());
            return "S" + seen.size();
        };
    }

    @Test @DisplayName("summarise folds everything but the newest keepRecent messages into a summary once past 'after'")
    void summariseCompacts() {
        var ctx = session(4);                                  // 8 messages
        var seen = new ArrayList<List<String>>();
        var policy = HistoryPolicy.summarise().keepRecent(2).after(6);

        boolean changed = policy.compact(ctx, summariser(seen));

        assertThat(changed).isTrue();
        assertThat(seen).containsExactly(List.of("u0", "a0", "u1", "a1", "u2", "a2"));
        assertThat(ctx.summary()).isEqualTo("S1");
        assertThat(ctx.messages()).extracting(ConversationContext.Message::content).containsExactly("u3", "a3");
    }

    @Test @DisplayName("nothing is summarised while the session is within 'after'")
    void summariseWaits() {
        var ctx = session(3);                                  // 6 messages, not over 6
        var seen = new ArrayList<List<String>>();

        boolean changed = HistoryPolicy.summarise().keepRecent(2).after(6).compact(ctx, summariser(seen));

        assertThat(changed).isFalse();
        assertThat(seen).isEmpty();
        assertThat(ctx.summary()).isNull();
        assertThat(ctx.messages()).hasSize(6);
    }

    @Test @DisplayName("what is kept starts with a user turn: an assistant reply on the boundary goes into the summary")
    void summariseBoundary() {
        var ctx = session(4);                                  // 8 messages
        var seen = new ArrayList<List<String>>();

        // keepRecent(3) would begin the kept messages at "a2", an assistant reply
        HistoryPolicy.summarise().keepRecent(3).after(6).compact(ctx, summariser(seen));

        assertThat(ctx.messages()).extracting(ConversationContext.Message::content).containsExactly("u3", "a3");
        assertThat(seen.get(0)).endsWith("a2");
    }

    @Test @DisplayName("a second summary continues the first")
    void summariseContinues() {
        var ctx = session(4);
        var previous = new ArrayList<String>();
        HistoryPolicy.Summariser summariser = (prev, older) -> { previous.add(prev); return "S" + (previous.size()); };
        var policy = HistoryPolicy.summarise().keepRecent(2).after(6);

        policy.compact(ctx, summariser);                        // -> S1, 2 messages left
        for (int i = 4; i < 8; i++) { ctx.addMessage("user", "u" + i); ctx.addMessage("assistant", "a" + i); }
        policy.compact(ctx, summariser);                        // sees S1 as the previous summary

        assertThat(previous).containsExactly(null, "S1");
        assertThat(ctx.summary()).isEqualTo("S2");
    }

    @Test @DisplayName("a blank summary changes nothing")
    void blankSummary() {
        var ctx = session(4);

        boolean changed = HistoryPolicy.summarise().keepRecent(2).after(6).compact(ctx, (p, older) -> "  ");

        assertThat(changed).isFalse();
        assertThat(ctx.messages()).hasSize(8);
    }

    @Test @DisplayName("if summarising keeps failing, no more than 'after' messages are sent")
    void summariseBackstop() {
        var h = HistoryPolicy.summarise().keepRecent(2).after(6).select(session(10));

        assertThat(h.messages()).hasSize(6);
    }

    @Test @DisplayName("settings that cannot work are refused")
    void summariseValidation() {
        assertThatThrownBy(() -> HistoryPolicy.summarise().keepRecent(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HistoryPolicy.summarise().after(2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HistoryPolicy.summarise().keepRecent(10).after(10).select(session(1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("greater than keepRecent");
    }

    // -- the stored form ------------------------------------------------------------------------------

    @Test @DisplayName("compact() refuses to fold more messages than there are")
    void compactBounds() {
        assertThatThrownBy(() -> session(1).compact(3, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
