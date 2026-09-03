package io.acme.claims;

import io.cafeai.core.CafeAI;
import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.memory.RedisConfig;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.connect.Fallback;
import io.cafeai.connect.Ollama;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.observability.ObserveStrategy;
import io.cafeai.rag.EmbeddingModel;
import io.cafeai.rag.Retriever;
import io.cafeai.rag.VectorStore;
import io.cafeai.security.AiSecurity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Acme Insurance Group — AI-powered claims intake assistant.
 *
 * <p>Accepts claim submissions via HTTP and WebSocket. Classifies the
 * claim type, verifies policy coverage, looks up or opens a claim via
 * the Acme claims API, and returns a structured {@link ClaimsDecision}.
 *
 * <p>Enforces HIPAA privacy, fraud escalation guardrails, and injection
 * security on every request.
 */
public class ClaimsApp {

    private static final Logger audit =
            LoggerFactory.getLogger("acme.audit");

    static final String SYSTEM_PROMPT = """
            You are a claims intake assistant for Acme Insurance Group,
            a regional property and casualty insurer.

            Your role is to help claimants report incidents and open
            insurance claims accurately and efficiently. You are
            professional, empathetic, and precise.

            You never share one claimant's information with another party.
            You never speculate about fault or liability.
            You never promise specific payout amounts.
            You never discuss medical details beyond what is needed for
            the claim type classification.

            REQUIRED TOOL PROTOCOL — follow exactly, in this order:
            1. If the claimant provides a claim number, ALWAYS call
               lookupClaim first. Report the result and stop.
            2. If opening a new claim: ALWAYS call verifyPolicyCoverage
               with the policy number before openClaim.
               If policy is NOT active, do not open a claim.
            3. ALWAYS call openClaim after verifyPolicyCoverage confirms
               coverage, unless the coverage is denied.
            Skipping these tools is not permitted.

            Decision rules:
            - CLAIM_OPENED:  new claim successfully created via openClaim tool
            - CLAIM_EXISTS:  existing claim found via lookupClaim tool
            - NOT_COVERED:   incident type not covered by policy or policy lapsed
            - ESCALATED:     claim meets escalation criteria (high value, fraud
                             indicators, legal representation, disputed liability)

            When answering a follow-up question, reply in plain language from the
            conversation history — do not call tools again.""";

    public static void main(String[] args) {
        var app = CafeAI.create();

        // ── AI provider ────────────────────────────────────────────
        app.connect(
                Ollama.at("http://localhost:11434").model("qwen2.5")
                        .onUnavailable(Fallback.use(OpenAI.gpt4oMini()))
        );

        // ── Memory ─────────────────────────────────────────────────
        // Full builder
        app.memory(MemoryStrategy.redis(
                RedisConfig.builder()
                        .host("localhost")
                        .port(6379)
                        .sessionTtl(Duration.ofHours(8))  // claims sessions — 8hr work day
                        .build()));

        // ── RAG pipeline ───────────────────────────────────────────
        app.vectordb(VectorStore.chroma("http://localhost:8000", "acme-claims"));
        app.embed(EmbeddingModel.local());
        app.rag(Retriever.semantic(3));

        // ── Observability ──────────────────────────────────────────
        app.observe(ObserveStrategy.console());

        // ── Knowledge base ─────────────────────────────────────────
        AcmeKnowledgeBase.seed(app);

        // ── The claims agent ──────────────────────────────────────
        // Inherits the Chroma-backed RAG and Redis session memory from
        // the app; adds the claims-API tool protocol and structured output.
        app.agent("claims", ClaimsAgent.class)
                .system(SYSTEM_PROMPT)
                .tool(new ClaimsApiTools());

        // ── Guardrails ─────────────────────────────────────────────
        app.guard(GuardRail.promptInjection());
        app.guard(GuardRail.jailbreak());

        // HIPAA — protect medical information
        app.guard(GuardRail.regulatory().hipaa());

        // Fraud — block attempts to coach the system on fraud patterns
        app.guard(GuardRail.topicBoundary()
                .allow("claim", "claims", "insurance", "policy", "coverage",
                        "incident", "accident", "damage", "injury", "theft",
                        "collision", "fire", "flood", "storm", "hail", "wind",
                        "liability", "property", "auto", "vehicle", "medical",
                        "adjuster", "deductible", "premium", "acme", "report",
                        "status", "number", "date", "amount", "repair", "estimate",
                        "police", "hospital", "doctor", "photo", "document",
                        "submit", "file", "open", "check", "lookup", "review",
                        "approved", "denied", "pending", "covered", "excluded")
                .deny("fraud", "fake", "exaggerate", "inflate", "stage",
                        "lie", "fabricate", "manufacture", "how to cheat",
                        "how to fake"));

        // ── Security ───────────────────────────────────────────────
        AiSecurity.onEvent(event -> {
            audit.warn("[SECURITY] type={} path={} eventId={}",
                    event.getClass().getSimpleName(),
                    event.requestPath(),
                    event.eventId());
        });
        app.filter(AiSecurity.promptInjectionDetector());

        // ── HTTP middleware ─────────────────────────────────────────
        app.filter(Middleware.requestLogger());
        app.filter(CafeAI.json());

        // ── Health endpoint ────────────────────────────────────────
        app.get("/health", (req, res, next) ->
                res.json(Map.of(
                        "status",  "ok",
                        "service", "acme-claims",
                        "insurer", "Acme Insurance Group"
                )));

        // ── Claims intake endpoint ─────────────────────────────────
        app.post("/claims", (req, res, next) -> {
            String sessionId  = req.header("X-Session-Id");
            String message    = req.body("message");

            // Follow-up path: session present, only message field
            boolean isFollowUp = sessionId != null && !sessionId.isBlank()
                    && message   != null && !message.isBlank()
                    && req.body("claimantId") == null;

            if (isFollowUp) {
                String answer = app.agent("claims", ClaimsAgent.class, sessionId)
                        .followUp(message);
                res.json(Map.of("answer", answer));
                return;
            }

            // Full claims request — validate required fields
            List<String> errors = validateRequest(req);
            if (!errors.isEmpty()) {
                res.status(400).json(Map.of(
                        "error",  "Invalid request",
                        "fields", errors
                ));
                return;
            }

            String claimantId    = req.body("claimantId");
            String policyNumber  = req.body("policyNumber");
            String incidentDate  = req.body("incidentDate");
            String incidentDesc  = req.body("incidentDescription");
            String claimNumber   = req.body("claimNumber"); // optional — for lookups

            // Build the prompt
            String prompt;
            if (claimNumber != null && !claimNumber.isBlank()) {
                // Status lookup flow
                prompt = String.format("""
                    Claimant %s is requesting the status of existing claim %s
                    on policy %s.
                    Look up the claim and provide a full status update.
                    """, claimantId, claimNumber, policyNumber);
            } else {
                // New claim flow
                prompt = String.format("""
                    Claimant %s is filing a new insurance claim on policy %s.
                    Incident date: %s
                    Incident description: %s

                    Verify the policy coverage, then open a new claim if covered.
                    Classify the claim type (AUTO/PROPERTY/LIABILITY/MEDICAL),
                    list required documentation, and provide next steps.
                    """,
                        claimantId, policyNumber, incidentDate, incidentDesc);
            }

            try {
                ClaimsDecision decision =
                        app.agent("claims", ClaimsAgent.class, sessionId).intake(prompt);
                audit.info("[AUDIT] claimantId={} policyNumber={} decision={} claimType={} claimNumber={}",
                        decision.claimantId(), decision.policyNumber(),
                        decision.decision(), decision.claimType(), decision.claimNumber());
                res.json(decision);
            } catch (Exception e) {
                audit.warn("[AUDIT] claimantId={} agentError={}", claimantId, e.toString());
                res.status(502).json(Map.of(
                        "claimantId", claimantId,
                        "error", "Claims intake failed: " + e.getMessage()));
            }
        });

        // ── WebSocket ──────────────────────────────────────────────
        var activeSessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

        app.ws("/ws/claims", new io.cafeai.core.routing.WsHandler() {
            @Override
            public void onOpen(io.cafeai.core.routing.WsSession session) {
                activeSessions.add(session);
                session.send("{\"type\":\"connected\","
                        + "\"message\":\"Acme Insurance claims assistant ready. "
                        + "Please describe your incident or provide your claim number.\","
                        + "\"sessionId\":\"" + session.id() + "\"}");
            }

            @Override
            public void onMessage(io.cafeai.core.routing.WsSession session,
                                  String rawMessage) {
                Thread.ofVirtual().start(() -> {
                    try {
                        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        @SuppressWarnings("unchecked")
                        var body = mapper.readValue(rawMessage, java.util.Map.class);
                        String text = body.getOrDefault("message", "").toString();

                        if (text.isBlank()) {
                            session.send("{\"type\":\"error\","
                                    + "\"message\":\"Field 'message' is required\"}");
                            return;
                        }

                        // WebSocket is the adjuster's conversational channel.
                        String answer = app.agent("claims", ClaimsAgent.class,
                                session.id()).followUp(text);
                        String escaped = answer
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n");
                        session.send("{\"type\":\"response\",\"answer\":\"" + escaped + "\"}");
                    } catch (Exception e) {
                        String msg = e.getMessage() != null
                                ? e.getMessage().replace("\"", "'") : "Internal error";
                        session.send("{\"type\":\"error\",\"message\":\"" + msg + "\"}");
                    }
                });
            }

            @Override
            public void onClose(io.cafeai.core.routing.WsSession session,
                                int code, String reason) {
                activeSessions.remove(session);
            }

            @Override
            public void onError(io.cafeai.core.routing.WsSession session,
                                Throwable error) {
                activeSessions.remove(session);
            }
        });

        app.listen(8080, () ->
                System.out.println("acme-claims running on http://localhost:8080"));
    }

    // ── Request validation ─────────────────────────────────────────

    private static List<String> validateRequest(
            io.cafeai.core.routing.Request req) {
        List<String> errors = new ArrayList<>();

        requireField(req, "claimantId",   errors);
        requireField(req, "policyNumber", errors);

        // claimNumber OR incidentDate + incidentDescription required
        String claimNumber = req.body("claimNumber");
        if (claimNumber == null || claimNumber.isBlank()) {
            requireField(req, "incidentDate",        errors);
            requireField(req, "incidentDescription", errors);

            String date = req.body("incidentDate");
            if (date != null && !date.isBlank() &&
                    !date.matches("\\d{4}-\\d{2}-\\d{2}")) {
                errors.add("'incidentDate' must be in YYYY-MM-DD format");
            }
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
}
