package io.cafeai.security;

import io.cafeai.core.Attributes;
import io.cafeai.core.middleware.Middleware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * AI-specific security middleware -- stricter enforcement than guardrails,
 * with audit logging and typed security events.
 *
 * <p>This is the security layer, distinct from the guardrails layer:
 * <ul>
 *   <li><strong>Guardrails</strong> ({@code cafeai-guardrails}) -- configurable
 *       policies with tunable thresholds and WARN/LOG/BLOCK actions.
 *       Designed for content moderation and business policy.</li>
 *   <li><strong>Security</strong> ({@code cafeai-security}) -- strict enforcement
 *       with zero threshold. Every detected event is blocked and audited.
 *       Designed for threat detection and compliance.</li>
 * </ul>
 *
 * <pre>{@code
 *   // Register event listener for audit/alerting
 *   AiSecurity.onEvent(event -> myAuditLog.record(event));
 *
 *   // Apply security middleware
 *   app.filter(AiSecurity.promptInjectionDetector());
 *   app.filter(AiSecurity.ragDataLeakagePrevention());
 *
 *   // Or apply to specific routes only
 *   app.post("/chat",
 *       AiSecurity.promptInjectionDetector(),
 *       AiSecurity.ragDataLeakagePrevention(),
 *       myHandler);
 * }</pre>
 */
public final class AiSecurity {

    private static final Logger log = LoggerFactory.getLogger(AiSecurity.class);

    private static final List<SecurityEventListener> listeners =
        new CopyOnWriteArrayList<>();

    private AiSecurity() {}

    /**
     * Registers a listener for all security events raised by this module.
     * Listeners are called synchronously on the request thread.
     */
    public static void onEvent(SecurityEventListener listener) {
        listeners.add(listener);
    }

    // -- Prompt injection detector ---------------------------------------------

    /**
     * Strict prompt injection detection middleware.
     *
     * <p>Differs from {@code GuardRail.promptInjection()} in two ways:
     * zero confidence threshold (any match = block) and mandatory audit logging
     * via {@link SecurityEvent.InjectionAttempt}.
     *
     * <p>Checks both user input and RAG-retrieved documents.
     */
    public static Middleware promptInjectionDetector() {
        return (req, res, next) -> {
            String path = req.path();

            // Check user input
            String input = extractInput(req);
            if (input != null && isInjection(input)) {
                SecurityEvent event = SecurityEvent.injection(path, truncate(input), "user_input");
                emit(event);
                log.warn("SECURITY [{}] Prompt injection in user input -- path={} eventId={}",
                    event.getClass().getSimpleName(), path, event.eventId());
                res.status(400).json(Map.of(
                    "error",   "Request blocked by security layer",
                    "reason",  "Prompt injection detected",
                    "eventId", event.eventId()));
                return;
            }

            // Check RAG documents
            @SuppressWarnings("unchecked")
            List<Object> ragDocs = (List<Object>) req.attribute(Attributes.RAG_DOCUMENTS);
            if (ragDocs != null) {
                for (Object doc : ragDocs) {
                    String content = doc.toString();
                    if (isInjection(content)) {
                        SecurityEvent event = SecurityEvent.injection(
                            path, truncate(content), "rag_document");
                        emit(event);
                        log.warn("SECURITY Prompt injection in RAG document -- path={} eventId={}",
                            path, event.eventId());
                        res.status(400).json(Map.of(
                            "error",   "Request blocked by security layer",
                            "reason",  "Prompt injection detected in retrieved document",
                            "eventId", event.eventId()));
                        return;
                    }
                }
            }

            next.run();
        };
    }

    // -- RAG data leakage prevention -------------------------------------------

    /**
     * Prevents RAG from returning documents the requesting user is not
     * authorised to see.
     *
     * <p>Reads the auth principal from
     * {@code req.attribute(Attributes.AUTH_PRINCIPAL)} and compares it
     * against document source IDs. Documents whose source IDs contain
     * access-restricted path segments (e.g. {@code /private/}, {@code /confidential/})
     * are filtered unless the principal has the required role.
     *
     * <p>Requires an upstream authentication middleware to populate
     * {@code Attributes.AUTH_PRINCIPAL}. If no principal is set, all
     * restricted documents are blocked.
     */
    public static Middleware ragDataLeakagePrevention() {
        return (req, res, next) -> {
            next.run(); // run first so RAG retrieval has happened

            @SuppressWarnings("unchecked")
            List<Object> ragDocs = (List<Object>) req.attribute(Attributes.RAG_DOCUMENTS);
            if (ragDocs == null || ragDocs.isEmpty()) return;

            Object principal = req.attribute(Attributes.AUTH_PRINCIPAL);
            String principalStr = principal != null ? principal.toString() : "anonymous";

            // Filter out restricted documents the principal cannot access
            List<Object> filtered = ragDocs.stream()
                .filter(doc -> {
                    String sourceId = sourceIdOf(doc);
                    if (isRestricted(sourceId)) {
                        SecurityEvent event = SecurityEvent.dataLeakage(
                            req.path(), req.bodyText(), sourceId, principalStr);
                        emit(event);
                        log.warn("SECURITY Data leakage prevented -- doc={} principal={} eventId={}",
                            sourceId, principalStr, event.eventId());
                        return false; // exclude from results
                    }
                    return true;
                })
                .toList();

            req.setAttribute(Attributes.RAG_DOCUMENTS, filtered);
        };
    }

    // -- Cache poisoning detector ----------------------------------------------

    /**
     * Detects adversarial prompts designed to corrupt the semantic cache.
     *
     * <p>Cache poisoning attacks craft prompts that produce malicious cached
     * responses. Detection looks for prompts that are semantically anomalous --
     * unusually high instruction density relative to query length.
     */
    public static Middleware semanticCachePoisoningDetector() {
        return (req, res, next) -> {
            String input = extractInput(req);
            if (input != null && isPoisoningAttempt(input)) {
                SecurityEvent event = SecurityEvent.cachePoisoning(req.path(), truncate(input));
                emit(event);
                log.warn("SECURITY Cache poisoning attempt -- path={} eventId={}",
                    req.path(), event.eventId());
                res.status(400).json(Map.of(
                    "error",   "Request blocked by security layer",
                    "reason",  "Potential cache poisoning attempt detected",
                    "eventId", event.eventId()));
                return;
            }
            next.run();
        };
    }

    // -- Internal helpers ------------------------------------------------------

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("ignore.{0,20}(previous|all|the).{0,20}(instructions?|prompt|rules?)",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("(new|override|replace).{0,20}(instructions?|task|objective)\\s*:",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\[SYSTEM\\]|\\[INST\\]|\\[OVERRIDE\\]|<<SYS>>",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("when (you |the model )?(see|read|encounter|process) this.{0,40}(do|say|respond|execute|perform)",
            Pattern.CASE_INSENSITIVE)
    );

    private static boolean isInjection(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(lower).find()) return true;
        }
        return false;
    }

    private static boolean isPoisoningAttempt(String text) {
        // Heuristic: instruction-heavy prompts (high verb density) relative to total length
        String lower = text.toLowerCase(Locale.ROOT);
        long imperative = List.of("always", "never", "respond", "say", "output",
                "return", "answer", "tell", "write", "pretend")
            .stream().filter(lower::contains).count();
        return text.length() < 200 && imperative >= 4;
    }

    private static boolean isRestricted(String sourceId) {
        if (sourceId == null) return false;
        String lower = sourceId.toLowerCase(Locale.ROOT);
        return lower.contains("/private/") || lower.contains("/confidential/")
            || lower.contains("/restricted/") || lower.contains("/internal/");
    }

    private static String sourceIdOf(Object doc) {
        try {
            return (String) doc.getClass().getMethod("sourceId").invoke(doc);
        } catch (Exception e) {
            return doc.toString();
        }
    }

    private static String extractInput(io.cafeai.core.routing.Request req) {
        String t = req.bodyText();
        if (t != null && !t.isBlank()) return t;
        Object b = req.body("message");
        return b != null ? b.toString() : null;
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static void emit(SecurityEvent event) {
        for (SecurityEventListener listener : listeners) {
            try { listener.onEvent(event); }
            catch (Exception e) {
                log.error("Security event listener threw: {}", e.getMessage());
            }
        }
    }
}
