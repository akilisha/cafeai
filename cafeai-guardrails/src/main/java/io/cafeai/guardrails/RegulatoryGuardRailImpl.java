package io.cafeai.guardrails;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Real regulatory compliance guardrail — GDPR, HIPAA, FCRA, CCPA.
 *
 * <p>Extends {@link GuardRail.RegulatoryGuardRail} so the {@code instanceof}
 * check in {@link GuardRail#regulatory()} resolves correctly, and the fluent
 * {@code .gdpr().hipaa()} API continues to work on the returned instance.
 */
public final class RegulatoryGuardRailImpl extends GuardRail.RegulatoryGuardRail {

    private static final Logger log = LoggerFactory.getLogger(RegulatoryGuardRailImpl.class);

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
        rp("FCRA", "consumer.{0,10}report.{0,20}(employment|tenant|insurance).{0,20}without")
    );

    private static final List<RegPattern> CCPA_PATTERNS = List.of(
        rp("CCPA", "sell.{0,10}personal.{0,10}(information|data).{0,20}without.{0,10}(opt.out|consent)"),
        rp("CCPA", "collect.{0,20}personal.{0,10}(information|data).{0,20}without.{0,10}disclos")
    );

    public RegulatoryGuardRailImpl() {}

    @Override public GuardRail.RegulatoryGuardRail gdpr()  { return addReg("gdpr",  GDPR_PATTERNS);  }
    @Override public GuardRail.RegulatoryGuardRail hipaa() { return addReg("hipaa", HIPAA_PATTERNS); }
    @Override public GuardRail.RegulatoryGuardRail fcra()  { return addReg("fcra",  FCRA_PATTERNS);  }
    @Override public GuardRail.RegulatoryGuardRail ccpa()  { return addReg("ccpa",  CCPA_PATTERNS);  }

    private RegulatoryGuardRailImpl addReg(String name, List<RegPattern> regPatterns) {
        activeRegs.add(name);
        patterns.addAll(regPatterns);
        return this;
    }

    @Override public String   name()     { return "regulatory[" + String.join(",", activeRegs) + "]"; }
    @Override public Position position() { return Position.BOTH; }
    @Override public Action   action()   { return Action.BLOCK; }

    @Override
    public void handle(Request req, Response res, Next next) {
        // PRE_LLM: check input
        String input = extractText(req);
        if (input != null && !input.isBlank()) {
            String violation = check(input);
            if (violation != null) {
                block(res, violation); return;
            }
        }

        next.run();
    }

    private String check(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (RegPattern rp : patterns) {
            if (rp.pattern().matcher(lower).find()) {
                return "Regulatory violation (" + rp.regulation() + ")";
            }
        }
        return null;
    }

    private void block(Response res, String reason) {
        log.warn("Regulatory guardrail triggered: {}", reason);
        res.status(400).json(Map.of(
            "error",     "Request blocked by guardrail",
            "guardrail", name(),
            "reason",    reason));
    }

    private static String extractText(Request req) {
        String t = req.bodyText();
        if (t != null && !t.isBlank()) return t;
        Object b = req.body("message");
        return b != null ? b.toString() : null;
    }

    private static RegPattern rp(String regulation, String regex) {
        return new RegPattern(regulation,
            Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
    }

    private record RegPattern(String regulation, Pattern pattern) {}
}
