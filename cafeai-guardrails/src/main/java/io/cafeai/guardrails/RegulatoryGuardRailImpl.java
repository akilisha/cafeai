package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.guardrails.TextNormalizer;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Real regulatory compliance guardrail — GDPR, HIPAA, FCRA, CCPA.
 *
 * <p>Implements {@link GuardRail.RegulatoryGuardRail}, the type
 * {@link GuardRail#regulatory()} returns, so the fluent {@code .gdpr().hipaa()} API works.
 */
public final class RegulatoryGuardRailImpl extends AbstractGuardRail implements GuardRail.RegulatoryGuardRail {

    private final Set<String>      activeRegs = new LinkedHashSet<>();
    private final List<RegPattern> patterns   = new ArrayList<>();

    // -- Regulation pattern sets -----------------------------------------------

    private static final List<RegPattern> GDPR_PATTERNS = List.of(
        rp("GDPR", "(share|export|transfer).{0,30}personal.{0,10}data.{0,20}(third.party|outside.eu|without.consent)"),
        rp("GDPR", "personal.{0,10}data.{0,30}(transfer|share|export).{0,30}(outside.eu|third.party|without.consent)"),
        rp("GDPR", "process.{0,10}personal.{0,10}data.{0,20}without.{0,10}consent"),
        rp("GDPR", "right.{0,10}to.{0,10}(be forgotten|erasure|deletion)")
    );

    private static final List<RegPattern> HIPAA_PATTERNS = List.of(
        rp("HIPAA", "(share|disclose|send).{0,30}(patient|medical).{0,10}(record|information|data)"),
        rp("HIPAA", "(patient|medical).{0,10}(record|information|data).{0,30}(share|disclose|send)"),
        rp("HIPAA", "(diagnosis|prescription|treatment).{0,20}without.{0,10}(consent|authorization)"),
        rp("HIPAA", "protected health information|\\bPHI\\b|\\bePHI\\b")
    );

    private static final List<RegPattern> FCRA_PATTERNS = List.of(
        rp("FCRA", "credit.{0,10}report.{0,20}without.{0,10}(consent|permissible purpose)"),
        rp("FCRA", "consumer.{0,10}report.{0,20}(employment|tenant|insurance).{0,20}without"),
        // Meridian-specific: exposing raw credit report data
        rp("FCRA", "show.{0,20}(their|the).{0,10}credit.{0,10}report"),
        rp("FCRA", "reveal.{0,20}(tradeline|credit.report|credit.file)"),
        rp("FCRA", "access.{0,20}(their|the).{0,10}credit.{0,10}(file|history|report)")
    );

    private static final List<RegPattern> CCPA_PATTERNS = List.of(
        rp("CCPA", "sell.{0,10}personal.{0,10}(information|data).{0,20}without.{0,10}(opt.out|consent)"),
        rp("CCPA", "collect.{0,20}personal.{0,10}(information|data).{0,20}without.{0,10}disclos")
    );

    // ECOA — Equal Credit Opportunity Act
    // Prohibits considering protected class characteristics in credit decisions
    private static final List<RegPattern> ECOA_PATTERNS = List.of(
        rp("ECOA", "\\b(race|color|religion|national.origin)\\b.{0,50}(loan|credit|qualify|mortgage|approval|decision)"),
        rp("ECOA", "(loan|credit|qualify|mortgage|approval|decision).{0,50}\\b(race|color|religion|national.origin)\\b"),
        rp("ECOA", "\\b(sex|gender|marital.status|marital)\\b.{0,50}(loan|credit|qualify|mortgage|approval)"),
        rp("ECOA", "(loan|credit|qualify|mortgage|approval).{0,50}\\b(sex|gender|marital.status)\\b"),
        rp("ECOA", "\\bage\\b.{0,30}(loan|credit|qualify|mortgage|approval|too old|too young)"),
        rp("ECOA", "(deny|decline|reject).{0,30}because.{0,30}(age|race|gender|religion|sex|national origin|marital)"),
        rp("ECOA", "applicant.{0,20}(race|ethnicity|nationality|religion|sex|gender|age|marital)")
    );

    // Fair Housing Act — prohibits steering based on protected characteristics
    private static final List<RegPattern> FAIR_HOUSING_PATTERNS = List.of(
        rp("FairHousing", "(avoid|steer|direct|recommend.against).{0,40}(neighborhood|area|zip.code|district).{0,30}(because|due.to|based.on)"),
        rp("FairHousing", "(neighborhood|area|zip.code).{0,30}(too many|mostly|predominantly|changing|transitional)"),
        rp("FairHousing", "(good|bad|safe|unsafe|desirable|undesirable).{0,20}(neighborhood|area|community).{0,30}(for|because of|due to)"),
        rp("FairHousing", "redlin(e|ing|ed)"),
        rp("FairHousing", "(don.t|do not|avoid).{0,20}lend.{0,30}(area|neighborhood|zip|district|block)")
    );

    public RegulatoryGuardRailImpl() {
        super(Action.BLOCK);
    }

    @Override public GuardRail.RegulatoryGuardRail gdpr()        { return addReg("gdpr",        GDPR_PATTERNS);         }
    @Override public GuardRail.RegulatoryGuardRail hipaa()       { return addReg("hipaa",       HIPAA_PATTERNS);        }
    @Override public GuardRail.RegulatoryGuardRail fcra()        { return addReg("fcra",        FCRA_PATTERNS);         }
    @Override public GuardRail.RegulatoryGuardRail ccpa()        { return addReg("ccpa",        CCPA_PATTERNS);         }
    @Override public GuardRail.RegulatoryGuardRail ecoa()        { return addReg("ecoa",        ECOA_PATTERNS);         }
    @Override public GuardRail.RegulatoryGuardRail fairHousing() { return addReg("fairHousing", FAIR_HOUSING_PATTERNS); }

    private RegulatoryGuardRailImpl addReg(String name, List<RegPattern> regPatterns) {
        activeRegs.add(name);
        patterns.addAll(regPatterns);
        return this;
    }

    @Override public String   name()     { return "regulatory[" + String.join(",", activeRegs) + "]"; }
    // Input only, as it always was. These patterns describe a discriminatory *request* ("deny the
    // loan because of race"); run over a response they would flag the model correctly refusing one.
    @Override public Position position() { return Position.PRE_LLM; }

    @Override
    protected CheckResult screenInput(String input) {
        String normal = TextNormalizer.normalize(input);
        for (RegPattern rp : patterns) {
            if (rp.pattern().matcher(normal).find()) {
                return CheckResult.block("Regulatory violation (" + rp.regulation() + ")");
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
