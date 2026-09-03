package io.meridian.qualify;

import io.cafeai.connect.Fallback;
import io.cafeai.connect.Ollama;
import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.WsHandler;
import io.cafeai.core.routing.WsSession;
import io.cafeai.observability.ObserveStrategy;
import io.cafeai.rag.EmbeddingModel;
import io.cafeai.rag.Retriever;
import io.cafeai.rag.VectorStore;
import io.cafeai.security.AiSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class QualifyApp {

    private static final Logger audit = LoggerFactory.getLogger("meridian.audit");

    static final String SYSTEM_PROMPT = """
            You are a pre-qualification assistant for Meridian Home Loans,
            a regional mortgage lender operating across the Midwest.

            Your role is to help applicants understand whether they are
            likely to qualify for a home loan based on their financial
            profile and Meridian's lending policies.

            You are factual, professional, and concise. You never make
            final credit decisions — you provide preliminary assessments
            that help applicants understand their position before speaking
            with a loan officer.

            You never ask for or discuss information about race, color,
            religion, national origin, sex, marital status, or age.
            These factors play no role in mortgage eligibility.

            Always refer to Meridian Home Loans by name.

            REQUIRED TOOL PROTOCOL — follow this exactly, in this order:
            1. ALWAYS call verifyLendingFootprint first with the property state.
               The tool result is final and authoritative — never override it.
               If DECLINED, set footprintStatus to DECLINED, decision to
               LIKELY_INELIGIBLE, and stop all other assessment.
            2. ALWAYS call calculateDTI with the applicant's income, debts,
               loan amount, and loan type. Never estimate DTI yourself.
            3. Use estimateMonthlyPayment when building your response.
            Skipping these tools is not permitted.

            Decision rules:
            - LIKELY_QUALIFIED:  footprint APPROVED + DTI within limit + credit above minimum
            - FURTHER_REVIEW:    footprint APPROVED + DTI within 5% of limit OR credit within 20 of minimum
            - LIKELY_INELIGIBLE: footprint DECLINED OR DTI exceeds limit OR credit below minimum

            dtiRatio is a decimal (e.g. 0.404 for 40.4%). Populate declineReasons
            only when LIKELY_INELIGIBLE. When answering a follow-up question, reply
            in plain language from the conversation history.""";

    public static void main(String[] args) {
        var app = CafeAI.create();

        // ── AI provider ────────────────────────────────────────────
        app.connect(
                Ollama.at("http://localhost:11434").model("qwen2.5")
                        .onUnavailable(Fallback.use(OpenAI.gpt4oMini()))
        );

        app.memory(MemoryStrategy.inMemory());

        // ── RAG pipeline ───────────────────────────────────────────
        app.vectordb(VectorStore.inMemory());
        app.embed(EmbeddingModel.local());
        app.rag(Retriever.semantic(3));

        // ── Observability ──────────────────────────────────────────
        app.observe(ObserveStrategy.console());

        // ── Policy knowledge base ──────────────────────────────────
        MeridianPolicyBase.seed(app);

        // ── The qualification agent ───────────────────────────────
        // Inherits the policy RAG and session memory from the app;
        // adds the forced tool protocol and structured output.
        app.agent("qualify", QualificationAgent.class)
                .system(SYSTEM_PROMPT)
                .tool(new QualificationTools());

        // ── Phase 7: Regulatory guardrails ────────────────────────
        // Order matters: ECOA runs first (protected attributes),
        // then FCRA (credit data exposure), then Fair Housing (steering).
        // Topic boundary runs last to catch general off-topic requests.

        app.guard(GuardRail.promptInjection());
        app.guard(GuardRail.jailbreak());

        app.guard(GuardRail.regulatory().ecoa());
        app.guard(GuardRail.regulatory().fcra());
        app.guard(GuardRail.regulatory().fairHousing());

        app.guard(GuardRail.topicBoundary()
                .allow("helios", "loan", "mortgage", "qualify", "qualification",
                        "income", "debt", "credit", "score", "dti", "conventional",
                        "fha", "va", "property", "state", "meridian", "lender",
                        "payment", "rate", "interest", "term", "down", "piti",
                        "applicant", "application", "borrow", "lend", "approve",
                        "decline", "footprint", "assess", "pre-qualify", "prequalify",
                        "wisconsin", "illinois", "minnesota", "michigan", "indiana",
                        "ohio", "iowa", "missouri", "kansas", "nebraska",
                        "north dakota", "south dakota", "wi", "il", "mn", "mi",
                        "in", "oh", "ia", "mo", "ks", "ne", "nd", "sd"));

        // ── Phase 10: Security layer ───────────────────────────────
        // Security events carry the request path for audit correlation.
        // In production: forward to SIEM with applicantId from request body.
        AiSecurity.onEvent(event -> {
            String type = event.getClass().getSimpleName();
            String path = event.requestPath();
            String eventId = event.eventId().toString();
            // Best-effort applicantId extraction for audit trail
            audit.warn("[SECURITY] type={} path={} eventId={}",
                    type, path, eventId);
        });

        // ── Middleware ─────────────────────────────────────────────
        app.filter(Middleware.requestLogger());
        app.filter(CafeAI.json());
        app.filter(AiSecurity.promptInjectionDetector());

        // ── Health ─────────────────────────────────────────────────
        app.get("/health", (req, res, next) ->
                res.json(Map.of(
                        "status", "ok",
                        "service", "meridian-qualify",
                        "lender", "Meridian Home Loans"
                )));

        // ── Pre-qualification endpoint ─────────────────────────────
        app.post("/qualify", (req, res, next) -> {
            String sessionId = req.header("X-Session-Id");
            String message = req.body("message");

            // ── Conversational follow-up: session present + only message field ──
            // Skip full validation — session memory carries the applicant context
            boolean isFollowUp = sessionId != null && !sessionId.isBlank()
                    && message != null && !message.isBlank()
                    && req.body("applicantId") == null;

            if (isFollowUp) {
                String answer = app.agent("qualify", QualificationAgent.class, sessionId)
                        .followUp(message);
                res.json(Map.of("answer", answer));
                return;
            }

            // ── Full qualification request: validate all fields ─────────────────
            List<String> errors = validateRequest(req);
            if (!errors.isEmpty()) {
                res.status(400).json(Map.of(
                        "error", "Invalid request",
                        "fields", errors
                ));
                return;
            }

            String applicantId = req.body("applicantId");
            String loanAmount = req.body("loanAmount");
            String annualIncome = req.body("annualIncome");
            String monthlyDebts = req.body("monthlyDebts");
            String creditScore = req.body("creditScore");
            String propertyState = req.body("propertyState");
            String loanType = req.body("loanType");

            String prompt = """
                    Pre-qualification request for applicant %s:
                    - Requested loan amount: $%s
                    - Annual income: $%s
                    - Monthly debt obligations: $%s
                    - Credit score: %s
                    - Property state: %s
                    - Loan type: %s
                    
                    Based on this financial profile and Meridian's lending policies,
                    provide a preliminary pre-qualification assessment. Be specific
                    about what looks strong, what may be a concern, and what the
                    applicant should prepare for a full application.
                    """.formatted(applicantId, loanAmount, annualIncome,
                    monthlyDebts, creditScore, propertyState, loanType);

            try {
                QualificationDecision decision =
                        app.agent("qualify", QualificationAgent.class, sessionId).assess(prompt);
                audit.info("[AUDIT] applicantId={} decision={} confidence={} dti={} footprint={}",
                        decision.applicantId(), decision.decision(), decision.confidence(),
                        decision.dtiRatio(), decision.footprintStatus());
                res.json(decision);
            } catch (Exception e) {
                audit.warn("[AUDIT] applicantId={} agentError={}", applicantId, e.toString());
                res.status(502).json(Map.of(
                        "applicantId", applicantId,
                        "error", "Assessment failed: " + e.getMessage()));
            }
        });

        // ── Phase 11: WebSocket streaming chat ────────────────────
// Loan officers use WebSocket for iterative file review.
// Session is anchored to applicantId from the first message.
        Set<WsSession> activeSessions = ConcurrentHashMap.newKeySet();

        app.ws("/ws/qualify", new WsHandler() {

            @Override
            public void onOpen(WsSession session) {
                activeSessions.add(session);
                session.send(json(
                        "type", "connected",
                        "message", "Meridian qualification assistant ready. "
                                + "Send applicant financial profile to begin assessment.",
                        "sessionId", session.id()
                ));
            }

            @Override
            public void onMessage(WsSession session, String message) {
                Thread.ofVirtual().start(() -> {
                    try {
                        // Parse the incoming message
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        @SuppressWarnings("unchecked")
                        var body = mapper.readValue(message,
                                java.util.Map.class);

                        String text = body.getOrDefault("message", "").toString();
                        if (text.isBlank()) {
                            session.send(json("type", "error",
                                    "message", "Field 'message' is required"));
                            return;
                        }

                        // WebSocket is the loan officer's iterative review channel —
                        // every message is a conversational follow-up on the session.
                        String answer = app.agent("qualify", QualificationAgent.class,
                                session.id()).followUp(text);
                        session.send(json("type", "response", "answer", answer));

                    } catch (Exception e) {
                        session.send(json("type", "error",
                                "message", e.getMessage() != null
                                        ? e.getMessage() : "Internal error"));
                    }
                });
            }

            @Override
            public void onClose(WsSession session, int code, String reason) {
                activeSessions.remove(session);
            }

            @Override
            public void onError(WsSession session, Throwable error) {
                activeSessions.remove(session);
            }

            // Minimal JSON builder for simple string key-value pairs
            private String json(String... kvPairs) {
                var sb = new StringBuilder("{");
                for (int i = 0; i < kvPairs.length; i += 2) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(kvPairs[i]).append("\":\"")
                            .append(kvPairs[i + 1]
                                    .replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n"))
                            .append('"');
                }
                return sb.append('}').toString();
            }
        });

        app.listen(8080, () ->
                System.out.println("meridian-qualify running on http://localhost:8080"));
    }

    // ── Validation ─────────────────────────────────────────────────

    private static List<String> validateRequest(io.cafeai.core.routing.Request req) {
        List<String> errors = new ArrayList<>();

        // Required string fields
        requireField(req, "applicantId", errors);
        requireField(req, "propertyState", errors);
        requireField(req, "loanType", errors);

        // Required numeric fields
        double loanAmount = requirePositiveNumber(req, "loanAmount", errors);
        double annualIncome = requirePositiveNumber(req, "annualIncome", errors);
        double creditScore = requirePositiveNumber(req, "creditScore", errors);
        requirePositiveNumber(req, "monthlyDebts", errors);

        // Loan type must be one of the supported types
        String loanType = req.body("loanType");
        if (loanType != null && !List.of("CONVENTIONAL", "FHA", "VA").contains(loanType)) {
            errors.add("loanType must be CONVENTIONAL, FHA, or VA");
        }

        // Credit score sanity range
        if (creditScore > 0 && (creditScore < 300 || creditScore > 850)) {
            errors.add("creditScore must be between 300 and 850");
        }

        // Loan amount sanity range — Meridian range: $50k to $2M
        if (loanAmount > 0 && (loanAmount < 50_000 || loanAmount > 2_000_000)) {
            errors.add("loanAmount must be between $50,000 and $2,000,000");
        }

        // Annual income sanity check
        if (annualIncome > 0 && annualIncome < 10_000) {
            errors.add("annualIncome appears too low — minimum $10,000 required");
        }

        return errors;
    }

    private static void requireField(
            io.cafeai.core.routing.Request req,
            String field,
            List<String> errors) {
        String val = req.body(field);
        if (val == null || val.isBlank()) {
            errors.add("'" + field + "' is required");
        }
    }

    private static double requirePositiveNumber(
            io.cafeai.core.routing.Request req,
            String field,
            List<String> errors) {
        String val = req.body(field);
        if (val == null || val.isBlank()) {
            errors.add("'" + field + "' is required");
            return 0;
        }
        try {
            double d = Double.parseDouble(val);
            if (d <= 0) {
                errors.add("'" + field + "' must be a positive number");
                return 0;
            }
            return d;
        } catch (NumberFormatException e) {
            errors.add("'" + field + "' must be a number");
            return 0;
        }
    }
}
