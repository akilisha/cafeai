package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Regulatory compliance guardrail — GDPR, HIPAA, FCRA, CCPA.
 *
 * <p>Each regulation adds a set of keyword patterns that are blocked from
 * both user input and LLM output. The guardrail is additive — enable
 * only the regulations relevant to your deployment.
 *
 * <pre>{@code
 *   app.guard(GuardRail.regulatory().gdpr());
 *   app.guard(GuardRail.regulatory().gdpr().hipaa());
 *   app.guard(GuardRail.regulatory().hipaa().ccpa());
 * }</pre>
 */
public final class RegulatoryGuardRailImpl extends AbstractGuardRail {

    private final Set<String>  activeRegs = new LinkedHashSet<>();
    private final List<RegPattern> patterns = new ArrayList<>();

    // ── Regulation pattern sets ───────────────────────────────────────────────

    private static final List<RegPattern> GDPR_PATTERNS = List.of(
        rp("GDPR", "personal.{0,10}data.{0,20}(transfer|export|share).{0,20}(third.party|outside.EU)"),
        rp("GDPR", "process.{0,10}personal.{0,10}data.{0,20}without.{0,10}consent"),
        rp("GDPR", "store.{0,10}(name|email|address|phone).{0,20}without.{0,10}(permission|consent)"),
        rp("GDPR", "right.{0,10}to.{0,10}(be forgotten|erasure|deletion)")
    );

    private static final List<RegPattern> HIPAA_PATTERNS = List.of(
        rp("HIPAA", "(patient|medical).{0,10}(record|information|data).{0,20}(share|disclose|send)"),
        rp("HIPAA", "(diagnosis|prescription|treatment).{0,20}(without|no).{0,10}(consent|authorization)"),
        rp("HIPAA", "protected health information|PHI|ePHI"),
        rp("HIPAA", "(SSN|social security).{0,20}(medical|health|patient)")
    );

    private static final List<RegPattern> FCRA_PATTERNS = List.of(
        rp("FCRA", "credit.{0,10}report.{0,20}(without|no).{0,10}(consent|authorization|permissible purpose)"),
        rp("FCRA", "consumer.{0,10}report.{0,20}(employment|tenant|insurance).{0,20}without"),
        rp("FCRA", "adverse.{0,10}action.{0,20}(without|no).{0,10}notice")
    );

    private static final List<RegPattern> CCPA_PATTERNS = List.of(
        rp("CCPA", "sell.{0,10}personal.{0,10}(information|data).{0,20}(without|no).{0,10}(opt.out|consent)"),
        rp("CCPA", "collect.{0,20}personal.{0,10}(information|data).{0,20}without.{0,10}disclos"),
        rp("CCPA", "california.{0,20}consumer.{0,20}privacy")
    );

    public RegulatoryGuardRailImpl() {
        super(Action.BLOCK);
    }

    RegulatoryGuardRailImpl(Action action) {
        super(action);
    }

    public RegulatoryGuardRailImpl gdpr()  { return addReg("GDPR",  GDPR_PATTERNS);  }
    public RegulatoryGuardRailImpl hipaa() { return addReg("HIPAA", HIPAA_PATTERNS); }
    public RegulatoryGuardRailImpl fcra()  { return addReg("FCRA",  FCRA_PATTERNS);  }
    public RegulatoryGuardRailImpl ccpa()  { return addReg("CCPA",  CCPA_PATTERNS);  }

    private RegulatoryGuardRailImpl addReg(String name, List<RegPattern> regPatterns) {
        activeRegs.add(name);
        patterns.addAll(regPatterns);
        return this;
    }

    @Override public String   name()     {
        return "regulatory[" + String.join(",", activeRegs) + "]";
    }
    @Override public GuardRail.Position position() { return GuardRail.Position.BOTH; }

    @Override
    protected CheckResult checkInput(String input) {
        return check(input);
    }

    @Override
    protected CheckResult checkOutput(String output) {
        return check(output);
    }

    private CheckResult check(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (RegPattern rp : patterns) {
            if (rp.pattern().matcher(lower).find()) {
                return CheckResult.block(
                    "Regulatory violation (" + rp.regulation() + "): " +
                    "content matches a restricted pattern", 0.9);
            }
        }
        return CheckResult.pass();
    }

    private static RegPattern rp(String regulation, String regex) {
        return new RegPattern(regulation,
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
    }

    private record RegPattern(String regulation, Pattern pattern) {}
}
