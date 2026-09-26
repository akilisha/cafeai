package io.cafeai.aiservices;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.GuardrailResult;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import io.cafeai.aiservices.adapter.GuardrailAdapters;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GuardrailAdapters}: a CafeAI guardrail applied by LangChain4j's {@code AiServices}.
 * They had no tests, ignored {@code GuardRail.Action}, would NPE on a null check result,
 * threw on a multimodal user message, and put the guardrail's reason into the exception
 * LangChain4j throws to the caller.
 */
class GuardrailAdaptersTest {

    private record Rail(GuardRail.Position position, GuardRail.Action action,
                        GuardRail.OutputCheckResult inputResult,
                        GuardRail.OutputCheckResult outputResult,
                        List<String> inputsSeen) implements GuardRail {
        @Override public String name() { return "test-rail"; }
        @Override public void handle(Request req, Response res, Next next) { next.run(); }
        @Override public OutputCheckResult checkInput(String text)  { inputsSeen.add(text); return inputResult; }
        @Override public OutputCheckResult checkOutput(String text) { return outputResult; }
    }

    private static Rail rail(GuardRail.Action action, GuardRail.OutputCheckResult in, GuardRail.OutputCheckResult out) {
        return new Rail(GuardRail.Position.BOTH, action, in, out, new ArrayList<>());
    }

    private static final GuardRail.OutputCheckResult BAD = GuardRail.OutputCheckResult.violation("SECRET-REASON-DETAIL");
    private static final GuardRail.OutputCheckResult OK  = GuardRail.OutputCheckResult.pass();

    private static boolean failed(GuardrailResult<?> r) { return r.result() == GuardrailResult.Result.FAILURE; }

    @Test @DisplayName("input: BLOCK fails, and the failure names the guardrail but not the reason")
    void inputBlockFailsWithoutReason() {
        InputGuardrailResult r = GuardrailAdapters.asInput(rail(GuardRail.Action.BLOCK, BAD, OK))
            .validate(UserMessage.from("hello"));

        assertThat(failed(r)).isTrue();
        assertThat(r.failures().toString()).contains("test-rail").doesNotContain("SECRET-REASON-DETAIL");
    }

    @Test @DisplayName("input: WARN and LOG let the call proceed")
    void inputWarnAndLogProceed() {
        for (var action : List.of(GuardRail.Action.WARN, GuardRail.Action.LOG)) {
            assertThat(failed(GuardrailAdapters.asInput(rail(action, BAD, OK)).validate(UserMessage.from("x"))))
                .as(action.name()).isFalse();
        }
    }

    @Test @DisplayName("input: a pass, or a null result from a sloppy guardrail, is a success")
    void inputPassAndNull() {
        assertThat(failed(GuardrailAdapters.asInput(rail(GuardRail.Action.BLOCK, OK, OK)).validate(UserMessage.from("x")))).isFalse();
        assertThat(failed(GuardrailAdapters.asInput(rail(GuardRail.Action.BLOCK, null, null)).validate(UserMessage.from("x")))).isFalse();
    }

    @Test @DisplayName("input: a multimodal message is screened on its text and does not throw")
    void inputMultimodal() {
        var rail = rail(GuardRail.Action.BLOCK, OK, OK);
        var message = UserMessage.from(
            ImageContent.from(Image.builder().base64Data("AQID").mimeType("image/png").build()),
            TextContent.from("describe this"));

        assertThat(failed(GuardrailAdapters.asInput(rail).validate(message))).isFalse();
        assertThat(rail.inputsSeen()).containsExactly("describe this");
    }

    @Test @DisplayName("output: BLOCK fails without the reason; WARN proceeds; a blank reply is skipped")
    void output() {
        OutputGuardrailResult blocked = GuardrailAdapters.asOutput(rail(GuardRail.Action.BLOCK, OK, BAD))
            .validate(AiMessage.from("some reply"));
        assertThat(failed(blocked)).isTrue();
        assertThat(blocked.failures().toString()).contains("test-rail").doesNotContain("SECRET-REASON-DETAIL");

        assertThat(failed(GuardrailAdapters.asOutput(rail(GuardRail.Action.WARN, OK, BAD))
            .validate(AiMessage.from("some reply")))).isFalse();
        assertThat(failed(GuardrailAdapters.asOutput(rail(GuardRail.Action.BLOCK, OK, BAD))
            .validate(AiMessage.from("   ")))).isFalse();
    }
}
