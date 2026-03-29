package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Adversarial prompt / jailbreak detection guardrail.
 *
 * <p>Detects attempts to bypass the system prompt, extract model internals,
 * or manipulate the model into ignoring its instructions.
 *
 * <p>Detection strategy — two layers, both must agree to block:
 * <ol>
 *   <li><strong>Pattern matching</strong> — fast, zero-latency check against
 *       known jailbreak patterns (DAN, role-play bypass, ignore-previous-instructions,
 *       base64 obfuscation, etc.)</li>
 *   <li><strong>Scoring</strong> — weighted sum of signals; blocks only when
 *       confidence exceeds threshold (default 0.7)</li>
 * </ol>
 *
 * <pre>{@code
 *   app.guard(GuardRail.jailbreak());
 *   app.guard(GuardRail.jailbreak().threshold(0.8));  // stricter
 *   app.guard(GuardRail.jailbreak().threshold(0.5));  // more sensitive
 * }</pre>
 */
public final class JailbreakGuardRail extends AbstractGuardRail {

    private double threshold;

    private static final double DEFAULT_THRESHOLD = 0.7;

    // ── Detection patterns ────────────────────────────────────────────────────

    private static final List<WeightedPattern> PATTERNS = List.of(
        // Role-play bypass
        wp(0.9, "ignore.{0,20}(previous|above|prior).{0,20}(instructions?|prompt|rules?)"),
        wp(0.9, "disregard.{0,20}(instructions?|prompt|rules?)"),
        wp(0.8, "you are now|pretend you are|act as if you are|roleplay as"),
        wp(0.8, "DAN|do anything now|jailbreak|unrestricted mode"),
        wp(0.7, "forget.{0,10}(you are|your|the|all).{0,10}(ai|assistant|rules|guidelines)"),

        // System prompt extraction
        wp(0.9, "reveal.{0,20}(system prompt|instructions|initial prompt)"),
        wp(0.9, "print.{0,20}(system prompt|your instructions|your rules)"),
        wp(0.8, "what (are|were) your (instructions|system prompt|initial prompt)"),
        wp(0.7, "repeat.{0,20}(everything|all).{0,20}(above|before|prior)"),

        // Obfuscation signals
        wp(0.6, "base64|rot13|hex.{0,10}encode|caesar cipher"),
        wp(0.5, "translate.{0,20}(to|into).{0,20}(your|the).{0,10}(true|real|actual)"),

        // Token manipulation
        wp(0.8, "\\[INST\\]|<<SYS>>|<\\|im_start\\|>|<\\|system\\|>"),

        // Hypothetical framing bypass
        wp(0.6, "hypothetically.{0,30}(if you could|you were able|without restrictions)"),
        wp(0.6, "in a fictional.{0,20}(world|scenario|story).{0,30}(explain|tell me|describe)")
    );

    public JailbreakGuardRail() {
        super(Action.BLOCK);
        this.threshold = DEFAULT_THRESHOLD;
    }

    JailbreakGuardRail(Action action, double threshold) {
        super(action);
        this.threshold = threshold;
    }

    /** Returns a new JailbreakGuardRail with the given confidence threshold (0.0–1.0). */
    public JailbreakGuardRail threshold(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("Threshold must be between 0.0 and 1.0");
        }
        return new JailbreakGuardRail(action(), threshold);
    }

    @Override public String   name()     { return "jailbreak"; }
    @Override public Position position() { return Position.PRE_LLM; }

    @Override
    protected CheckResult checkInput(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        double maxHit = 0.0;
        String topPattern = null;

        for (WeightedPattern wp : PATTERNS) {
            if (wp.pattern().matcher(lower).find() && wp.weight() > maxHit) {
                maxHit = wp.weight();
                topPattern = wp.pattern().pattern().substring(0, Math.min(40, wp.pattern().pattern().length()));
            }
        }

        if (maxHit >= threshold) {
            return CheckResult.block(
                String.format("Potential jailbreak attempt detected (confidence %.0f%%)",
                    maxHit * 100),
                maxHit);
        }
        return CheckResult.pass();
    }

    private static double score(String text) {
        double max = 0.0;
        for (WeightedPattern wp : PATTERNS) {
            if (wp.pattern().matcher(text).find() && wp.weight() > max) {
                max = wp.weight();
            }
        }
        return max;
    }

    private static WeightedPattern wp(double weight, String regex) {
        return new WeightedPattern(weight,
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
    }

    private record WeightedPattern(double weight, Pattern pattern) {}
}
