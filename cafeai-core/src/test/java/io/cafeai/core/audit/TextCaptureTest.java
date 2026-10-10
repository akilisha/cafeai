package io.cafeai.core.audit;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.ai.UsageReport;
import io.cafeai.core.internal.LangchainBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("app.auditText: what was asked and answered, opt-in, redacted, kept for a set time")
class TextCaptureTest {

    /** Answers by repeating the caller's last message, so the answer carries what the prompt did. */
    private static final class Echo implements AiProvider, LangchainBridge.ChatModelAccess,
            LangchainBridge.StreamingChatModelAccess {
        @Override public String       name()    { return "echo"; }
        @Override public String       modelId() { return "echo-1"; }
        @Override public ProviderType type()    { return ProviderType.CUSTOM; }

        private static ChatResponse answer(List<ChatMessage> messages) {
            String said = "";
            for (ChatMessage m : messages) if (m instanceof UserMessage u) said = u.singleText();
            return ChatResponse.builder().aiMessage(AiMessage.from("You said: " + said)).build();
        }

        @Override public ChatModel toChatModel() {
            return new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest r) { return answer(r.messages()); }
            };
        }

        @Override public StreamingChatModel toStreamingChatModel() {
            return new StreamingChatModel() {
                @Override public void doChat(ChatRequest r, StreamingChatResponseHandler h) {
                    ChatResponse response = answer(r.messages());
                    h.onPartialResponse(response.aiMessage().text());
                    h.onCompleteResponse(response);
                }
            };
        }
    }

    private static final String SENSITIVE = "Mail ann@example.com, key AKIAABCDEFGHIJKLMNOP, card 4111111111111111";

    @Test @DisplayName("each call's prompt and answer reach the transcript sink, redacted, with a keep-until")
    void capturedAndRedacted() {
        List<Transcript> kept = new CopyOnWriteArrayList<>();
        var app = CafeAI.create();
        app.ai(new Echo());
        app.auditText(TextCapture.to(kept::add).keepFor(Duration.ofDays(30)));

        String answer = app.prompt(SENSITIVE).call().text();
        assertThat(answer).as("the caller still gets the real answer").contains("ann@example.com");

        assertThat(kept).hasSize(1);
        Transcript t = kept.getFirst();
        assertThat(t.prompt()).isEqualTo("Mail [EMAIL], key [AWS_ACCESS_KEY_ID], card [CREDIT_CARD]");
        assertThat(t.answer()).isEqualTo("You said: Mail [EMAIL], key [AWS_ACCESS_KEY_ID], card [CREDIT_CARD]");
        assertThat(t.model()).isEqualTo("echo-1");
        assertThat(t.route()).isEqualTo(UsageReport.NO_REQUEST);
        assertThat(t.caller()).isNull();
        assertThat(Duration.between(t.at(), t.keepUntil())).isEqualTo(Duration.ofDays(30));
    }

    @Test @DisplayName("a streamed call is captured too")
    void streamed() {
        List<Transcript> kept = new CopyOnWriteArrayList<>();
        var app = CafeAI.create();
        app.ai(new Echo());
        app.auditText(TextCapture.to(kept::add).keepFor(Duration.ofDays(1)));

        app.prompt("call me on 555-867-5309").stream(token -> { });
        assertThat(kept).singleElement().satisfies(t -> {
            assertThat(t.prompt()).isEqualTo("call me on [PHONE]");
            assertThat(t.answer()).isEqualTo("You said: call me on [PHONE]");
        });
    }

    @Test @DisplayName("audit sinks stay metadata only: no text ever reaches them")
    void auditSinksGetNoText() {
        List<AuditEvent> events = new CopyOnWriteArrayList<>();
        var app = CafeAI.create();
        app.ai(new Echo());
        app.audit(events::add);
        app.auditText(TextCapture.to(t -> { }).keepFor(Duration.ofDays(1)));

        app.prompt("project bluebird launches friday").call();
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(e -> assertThat(e.toString()).doesNotContain("bluebird"));
    }

    @Test @DisplayName("nothing is captured unless the app asks")
    void offByDefault() {
        List<AuditEvent> events = new CopyOnWriteArrayList<>();
        var app = CafeAI.create();
        app.ai(new Echo());
        app.audit(events::add);
        app.prompt("hello").call();
        assertThat(events).allSatisfy(e -> assertThat(e).isNotInstanceOf(Transcript.class));
    }

    @Test @DisplayName("the app must say how long text is kept: there is no default to forget")
    void retentionRequired() {
        var app = CafeAI.create();
        assertThatThrownBy(() -> app.auditText(TextCapture.to(t -> { })))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("keepFor");
        assertThatThrownBy(() -> TextCapture.to(t -> { }).keepFor(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("an organisation's own terms can be redacted on top of the standard ones")
    void ownRedactor() {
        List<Transcript> kept = new CopyOnWriteArrayList<>();
        var app = CafeAI.create();
        app.ai(new Echo());
        app.auditText(TextCapture.to(kept::add).keepFor(Duration.ofDays(1))
                .redactWith(Redactor.standard().andThen(text -> text.replace("bluebird", "[PROJECT]"))));

        app.prompt("bluebird: ann@example.com").call();
        assertThat(kept.getFirst().prompt()).isEqualTo("[PROJECT]: [EMAIL]");
    }

    @Test @DisplayName("a transcript sink that fails is skipped: the call still succeeds")
    void failingSink() {
        var app = CafeAI.create();
        app.ai(new Echo());
        app.auditText(TextCapture.to(t -> { throw new IllegalStateException("disk full"); }).keepFor(Duration.ofDays(1)));
        assertThat(app.prompt("hi").call().text()).isEqualTo("You said: hi");
    }

    // -- the JSON-lines sink keeps text no longer than it may --------------------------------------

    private static Transcript transcript(Instant at, Duration keep, String prompt) {
        return new Transcript(at, null, UsageReport.NO_REQUEST, "m", prompt, "a", at.plus(keep));
    }

    @Test @DisplayName("jsonLines files records by the day they expire, and deletes a day's file once it has passed")
    void jsonLinesRetention(@TempDir Path dir) throws Exception {
        Instant day1 = Instant.parse("2026-10-01T10:00:00Z");
        var early = new TranscriptSink.JsonLines(dir, Clock.fixed(day1, ZoneOffset.UTC));
        early.record(transcript(day1, Duration.ofDays(1), "short-lived"));    // gone after 2026-10-02
        early.record(transcript(day1, Duration.ofDays(30), "long-lived"));   // gone after 2026-10-31

        assertThat(Files.readString(dir.resolve("until-2026-10-02.jsonl")))
                .contains("\"prompt\":\"short-lived\"").contains("\"keepUntil\":\"2026-10-02T10:00:00Z\"");
        assertThat(dir.resolve("until-2026-10-31.jsonl")).exists();

        // Three days on, the next write purges the file whose day has passed, and only that one.
        Instant day4 = Instant.parse("2026-10-04T09:00:00Z");
        var later = new TranscriptSink.JsonLines(dir, Clock.fixed(day4, ZoneOffset.UTC));
        later.record(transcript(day4, Duration.ofDays(30), "another"));
        assertThat(dir.resolve("until-2026-10-02.jsonl")).doesNotExist();
        assertThat(dir.resolve("until-2026-10-31.jsonl")).exists();
        assertThat(dir.resolve("until-2026-11-03.jsonl")).exists();

        List<String> lines = new ArrayList<>(Files.readAllLines(dir.resolve("until-2026-10-31.jsonl")));
        assertThat(lines).singleElement().asString().contains("long-lived");
    }
}
