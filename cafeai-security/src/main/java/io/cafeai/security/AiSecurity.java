package io.cafeai.security;

import io.cafeai.core.guardrails.GuardRail;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.Request;
import io.cafeai.guardrails.PromptInjectionGuardRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An audit trail for blocked requests: {@link SecurityEvent}s with a unique id, delivered to your
 * own {@link SecurityEventListener} (a SIEM, an audit table, an alert).
 *
 * <p>This is what {@code cafeai-guardrails} does not do. A guardrail blocks and logs; this blocks,
 * logs, and hands you a typed, correlatable record of what was blocked.
 *
 * <pre>{@code
 *   AiSecurity.onEvent(event -> myAuditLog.record(event));
 *   app.filter(AiSecurity.promptInjectionDetector());
 * }</pre>
 *
 * <p><strong>Scope.</strong> {@link #promptInjectionDetector()} is HTTP middleware: it sees the
 * request body of the routes it is applied to, and nothing else. It does <em>not</em> screen a call
 * made from your own code ({@code app.prompt(...)} outside a request), an agent, or the documents
 * RAG retrieves. For those, register {@code app.guard(GuardRail.promptInjection())}, which the
 * engine enforces on every call and which screens retrieved documents. Use both when you want the
 * audit events and the engine's coverage.
 */
public final class AiSecurity {

    private static final Logger log = LoggerFactory.getLogger(AiSecurity.class);

    private static final List<SecurityEventListener> listeners = new CopyOnWriteArrayList<>();

    private AiSecurity() {}

    /**
     * Registers a listener for all security events raised by this module.
     * Listeners are called synchronously on the request thread; one that throws is logged and
     * skipped, and never prevents the request being blocked.
     */
    public static void onEvent(SecurityEventListener listener) {
        listeners.add(listener);
    }

    /**
     * Blocks a request whose message carries an injected instruction, and raises an
     * {@link SecurityEvent.InjectionAttempt}. Detection is {@code GuardRail.promptInjection()}'s
     * (normalised text; see {@code PromptInjectionGuardRail}), so the two never disagree about what
     * an injection is. The {@code 400} body carries an event id for correlation and no reason: why
     * it tripped is in the log, where it cannot help an attacker rephrase.
     *
     * <p>Reads the message from {@code req.body("message")} or {@code "prompt"}, or the raw body.
     */
    public static Middleware promptInjectionDetector() {
        GuardRail detector = new PromptInjectionGuardRail();
        return (req, res, next) -> {
            String input = extractInput(req);
            if (input != null && detector.checkInput(input).isViolation()) {
                String path = req.path();
                SecurityEvent event = SecurityEvent.injection(path, truncate(input));
                emit(event);
                log.warn("SECURITY prompt injection blocked -- path={} eventId={}", path, event.eventId());
                res.status(400).json(Map.of(
                    "error",   "Request blocked by security layer",
                    "eventId", event.eventId()));
                return;
            }
            next.run();
        };
    }

    // -- Internal helpers ------------------------------------------------------

    private static String extractInput(Request req) {
        Object b = req.body("message");
        if (b != null) return b.toString();
        b = req.body("prompt");
        if (b != null) return b.toString();
        String t = req.bodyText();
        if (t != null && !t.isBlank()) {
            String trimmed = t.trim();
            if (trimmed.startsWith("{")) {
                String msg = extractJsonField(trimmed, "message");
                if (msg == null) msg = extractJsonField(trimmed, "prompt");
                if (msg != null) return msg;
            }
            return t;
        }
        return null;
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;
        int colonIdx = json.indexOf(':', keyIdx + key.length());
        if (colonIdx < 0) return null;
        int valueStart = colonIdx + 1;
        while (valueStart < json.length()
               && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') return null;
        valueStart++;
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n'  -> sb.append('\n');
                    case 't'  -> sb.append('\t');
                    default   -> sb.append(next);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
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
