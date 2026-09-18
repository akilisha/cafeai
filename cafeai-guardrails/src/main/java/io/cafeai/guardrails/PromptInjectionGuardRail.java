package io.cafeai.guardrails;

import io.cafeai.core.guardrails.TextNormalizer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt injection detection guardrail.
 *
 * <p>Detects instructions smuggled to the model through:
 * <ul>
 *   <li><strong>User input</strong> — direct injection in the user's message.</li>
 *   <li><strong>RAG documents</strong> — indirect injection: a retrieved document contains hidden
 *       instructions. The source is your own knowledge base, so it looks trusted, which is what
 *       makes it dangerous. The engine screens each retrieved document with this guardrail
 *       ({@code checkRetrieved}) and <em>drops</em> one that trips it; the question is still
 *       answered from the rest.</li>
 * </ul>
 *
 * <p>Text is {@linkplain TextNormalizer#normalize normalised} first, so full-width letters,
 * zero-width characters, homoglyphs and accents do not hide a phrase. It is a pattern list, not a
 * model: a rephrasing it does not know gets through. Pair it with
 * {@code GuardRail.moderation(model)} where that matters.
 *
 * <pre>{@code
 *   app.guard(GuardRail.promptInjection());
 * }</pre>
 */
public final class PromptInjectionGuardRail extends AbstractGuardRail {

    /** Instructions addressed to the model — suspicious in a user's message and in a document. */
    private static final List<Pattern> INSTRUCTIONS = List.of(
        p("\\b(?:ignore|disregard|forget|override)\\s+(?:all\\s+|any\\s+|the\\s+|your\\s+|my\\s+)*"
            + "(?:previous\\s+|prior\\s+|above\\s+|earlier\\s+|preceding\\s+)*"
            + "(?:instructions?|prompts?|rules?|guidelines?|directions?)"),
        p("\\bnew\\s+(?:instructions?|task|objective|goal)\\s*:"),
        // A role marker opening a sentence, as in a transcript: "System: do X". Mid-sentence
        // ("operating system: linux") is ordinary text.
        p("(?:^|[.!?>\\]])\\s*(?:system|assistant)\\s*:"),
        p("\\[(?:system|inst|override)\\]|<\\|im_start\\|>|<<sys>>"),
        p("\\b(?:the\\s+following|these)\\s+(?:instructions?\\s+|commands?\\s+)?(?:override|supersede|replace)"),
        p("\\b(?:act|behave|respond)\\s+(?:as\\s+if|like)\\s+(?:you\\s+are|you're)")
    );

    /** Only meaningful in data: a document has no business addressing the model. */
    private static final List<Pattern> DOCUMENT_ONLY = List.of(
        p("<!--.{0,400}?(?:ignore|instruction|assistant|system|you\\s+must|you\\s+should|do\\s+not\\s+tell|reveal)"),
        p("\\bwhen\\s+(?:you\\s+|the\\s+model\\s+|an?\\s+(?:ai|assistant|llm)\\s+)?(?:see|read|encounter|process)\\s+this"),
        p("\\b(?:ai|assistant|llm|model|chatbot)[,\\s]+(?:you\\s+)?(?:must|should|will\\s+now|are\\s+to)\\b"),
        p("\\bdo\\s+not\\s+(?:tell|inform|reveal\\s+to)\\s+the\\s+user")
    );

    public PromptInjectionGuardRail() {
        super(Action.BLOCK);
    }

    PromptInjectionGuardRail(Action action) {
        super(action);
    }

    /** A copy that {@code BLOCK}s, or only {@code WARN}s / {@code LOG}s, on detection. */
    public PromptInjectionGuardRail action(Action action) {
        return new PromptInjectionGuardRail(action);
    }

    @Override public String   name()     { return "prompt-injection"; }
    @Override public Position position() { return Position.PRE_LLM; }

    @Override
    protected CheckResult screenInput(String input) {
        return matches(INSTRUCTIONS, input)
            ? CheckResult.block("Prompt injection detected in user input")
            : CheckResult.pass();
    }

    @Override
    public OutputCheckResult checkRetrieved(String documentText) {
        return matches(INSTRUCTIONS, documentText) || matches(DOCUMENT_ONLY, documentText)
            ? OutputCheckResult.violation("Prompt injection detected in a retrieved document")
            : OutputCheckResult.pass();
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        String normal = TextNormalizer.normalize(text);
        for (Pattern p : patterns) {
            if (p.matcher(normal).find()) return true;
        }
        return false;
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }
}
