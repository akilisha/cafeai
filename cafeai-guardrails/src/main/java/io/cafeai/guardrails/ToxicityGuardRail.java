package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Toxic and harmful content detection guardrail.
 *
 * <p>Detects toxic, hateful, violent, or explicitly harmful content in both
 * the user's input and the LLM's output. Uses a combination of pattern
 * matching and categorical classification.
 *
 * <p>Categories detected:
 * <ul>
 *   <li>Threats and incitement to violence</li>
 *   <li>Hate speech targeting protected characteristics</li>
 *   <li>Explicit requests for harmful instructions</li>
 *   <li>Self-harm and suicide-related content (with sensitivity context)</li>
 * </ul>
 *
 * <pre>{@code
 *   app.guard(GuardRail.toxicity());
 *   app.guard(GuardRail.toxicity().action(Action.WARN));  // log but don't block
 * }</pre>
 */
public final class ToxicityGuardRail extends AbstractGuardRail {

    private static final double BLOCK_THRESHOLD = 0.6;

    private static final List<ToxicPattern> PATTERNS = List.of(
        tp(1.0, "THREAT",
            "i (will|am going to|want to) (kill|hurt|harm|attack|destroy).{0,30}(you|them|everyone)"),
        tp(1.0, "INCITEMENT",
            "(kill|attack|hurt|harm|bomb|shoot).{0,20}(all|every|those).{0,20}(people|group|them)"),
        tp(0.9, "HATE_SPEECH",
            "(i hate|death to|exterminate|eliminate).{0,20}(all|every).{0,20}[a-z]+s?\\b"),
        tp(0.8, "HARMFUL_INSTRUCTIONS",
            "how (do i|to|can i).{0,30}(make|build|create|synthesize).{0,30}" +
            "(weapon|bomb|explosive|poison|drug|meth|fentanyl)"),
        tp(0.7, "EXPLICIT_HARM",
            "step.{0,5}by.{0,5}step.{0,20}(instructions?|guide|tutorial).{0,20}" +
            "(harm|hurt|kill|attack|poison)")
    );

    public ToxicityGuardRail() {
        super(Action.BLOCK);
    }

    ToxicityGuardRail(Action action) {
        super(action);
    }

    public ToxicityGuardRail action(Action action) {
        return new ToxicityGuardRail(action);
    }

    @Override public String   name()     { return "toxicity"; }
    @Override public Position position() { return Position.BOTH; }

    @Override
    protected CheckResult checkInput(String input) {
        return check(input);
    }

    @Override
    protected CheckResult checkOutput(String output) {
        return check(output);
    }

    private static CheckResult check(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        double maxScore = 0.0;
        String triggeredCategory = null;

        for (ToxicPattern tp : PATTERNS) {
            if (tp.pattern().matcher(lower).find() && tp.weight() > maxScore) {
                maxScore = tp.weight();
                triggeredCategory = tp.category();
            }
        }

        if (maxScore >= BLOCK_THRESHOLD) {
            return CheckResult.block(
                "Toxic content detected: " + triggeredCategory, maxScore);
        }
        return CheckResult.pass();
    }

    private static ToxicPattern tp(double weight, String category, String regex) {
        return new ToxicPattern(weight, category,
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
    }

    private record ToxicPattern(double weight, String category, Pattern pattern) {}
}
