package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Credentials;
import io.cafeai.core.ai.OpenAI;
import io.cafeai.core.ai.UsageReport;
import io.cafeai.core.audit.AuditEvent;
import io.cafeai.core.identity.Identity;
import io.cafeai.core.memory.MemoryStrategy;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.routing.Request;
import io.cafeai.core.session.Session;
import io.cafeai.core.session.SessionStore;
import io.cafeai.identity.Auth;
import io.cafeai.identity.Issuer;
import io.cafeai.identity.OAuthCredentials;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IdentityChatExample: a chat app people sign in to, end to end, with a real issuer.
 *
 * <ul>
 *   <li>Browser sign-in through Keycloak (alice/alice or bob/bob); tokens stay on the server.</li>
 *   <li>Each model call is made <b>on behalf of the signed-in user</b>: their token is exchanged
 *       (RFC 8693) for one issued for the model gateway. No API key anywhere. The console prints
 *       whom each call's token names.</li>
 *   <li>Each user has their own conversation, even though both use the same conversation id.</li>
 *   <li>{@code /usage} shows model usage per user; the console prints every audit record.</li>
 * </ul>
 *
 * <p>The model is a local Ollama, through its OpenAI-compatible endpoint. Ollama ignores tokens,
 * so this shows the token being obtained and sent; a company's model gateway would also check it.
 *
 * <pre>
 *   docker compose -f cafeai-examples/identity/docker-compose.yml up -d   # Keycloak on :8180
 *   ollama pull llama3.2                                                   # once
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.IdentityChatExample
 *   # open http://localhost:8080, sign in as alice; then in a private window, as bob
 * </pre>
 */
public class IdentityChatExample {

    private static final String ISSUER = env("ISSUER", "http://localhost:8180/realms/cafeai-demo");
    private static final String MODEL = env("OLLAMA_MODEL", "llama3.2");

    /** Display names for the usage page, keyed like usage is: issuer and subject. */
    private static final Map<Identity.Key, String> NAMES = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        var issuer = Issuer.discover(ISSUER);
        var app = CafeAI.create();

        // ── The model, reached as the signed-in user: no API key ──────────────────────
        Credentials asTheUser = OAuthCredentials.tokenExchange(issuer, "chat-api", "chat-api-secret", "model-gateway");
        app.ai(OpenAI.of(MODEL)
            .withBaseUrl("http://localhost:11434/v1")
            .withCredentials(showWhoEachCallIsFor(asTheUser)));
        app.system("You are a concise assistant. Answer in at most three sentences.");
        app.memory(MemoryStrategy.inMemory());
        app.audit(event -> System.out.println("AUDIT  " + describe(event)));

        // ── Sign-in: a server-side session, form bodies, then OpenID Connect ──────────
        app.filter(Middleware.session(SessionStore.inMemory()));
        app.filter(CafeAI.urlencoded());
        app.filter(Auth.login(issuer, "chat-web", "chat-web-secret", "http://localhost:8080/auth/callback")
            .afterSignOut("http://localhost:8080/"));

        app.get("/", (req, res, next) -> res.type("text/html").send(req.identity().isPresent()
            ? chatPage(req) : welcomePage()));

        // Every user talks in the conversation "main": CafeAI keeps each user's apart.
        app.post("/chat", Auth.signedIn(), (req, res, next) -> {
            String message = req.body("message");
            if (message != null && !message.isBlank()) {
                Identity who = req.identity().orElseThrow();
                NAMES.put(who.key(), who.name().orElse(who.subject()));
                String answer = app.prompt(message).session("main").call().text();
                transcript(req.session()).add(new String[]{message, answer});
            }
            res.redirect("/");
        });

        app.get("/usage", (req, res, next) -> res.type("text/html").send(usagePage(app.usage())));

        app.listen(8080, () -> System.out.println("""

            Chat app on http://localhost:8080 (issuer %s, model %s via Ollama)
            Sign in as alice/alice, and as bob/bob in a private window.
            """.formatted(ISSUER, MODEL)));
    }

    /**
     * Wraps the credentials to print whom each model call's token was issued for. LangChain4j's
     * OpenAI client asks for the headers twice per call (it prepares a plain and a streaming
     * request, with the JSON serialised in between), and the second is served from the token
     * cache, so it is printed once.
     */
    private static Credentials showWhoEachCallIsFor(Credentials credentials) {
        ThreadLocal<long[]> lastPrinted = ThreadLocal.withInitial(() -> new long[]{0});
        return new Credentials() {
            @Override public String token() {
                String token = credentials.token();
                long now = System.nanoTime();
                boolean sameCall = now - lastPrinted.get()[0] < 1_000_000_000L;   // within a second, same thread
                lastPrinted.get()[0] = now;
                if (sameCall) return token;
                String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
                System.out.println("MODEL  call with a token for " + claim(claims, "preferred_username")
                    + ", issued for " + claim(claims, "aud") + ", requested by " + claim(claims, "azp"));
                return token;
            }
            @Override public boolean perCaller() { return credentials.perCaller(); }
        };
    }

    // ── pages ───────────────────────────────────────────────────────────────────────────

    private static String welcomePage() {
        return page("Welcome", """
            <p>Sign in to chat. Try <b>alice</b>/<b>alice</b>, then <b>bob</b>/<b>bob</b> in a private window:
            each gets their own conversation, and every model call carries that person's token.</p>
            <p><a class="button" href="/auth/login?return=/">Sign in</a></p>""");
    }

    private static String chatPage(Request req) {
        Identity who = req.identity().orElseThrow();
        String csrf = Auth.csrfToken(req).orElse("");
        StringBuilder log = new StringBuilder();
        for (String[] turn : transcript(req.session())) {
            log.append("<p class=\"you\">").append(html(turn[0])).append("</p>")
               .append("<p class=\"ai\">").append(html(turn[1])).append("</p>");
        }
        return page("Chat", """
            <p>Signed in as <b>%s</b> (groups: %s).
               <form method="post" action="/auth/logout" class="inline">
                 <input type="hidden" name="_csrf" value="%s"><button>Sign out</button></form>
               &middot; <a href="/usage">usage per user</a></p>
            %s
            <form method="post" action="/chat">
              <input type="hidden" name="_csrf" value="%s">
              <input name="message" autofocus placeholder="Say something" size="60"> <button>Send</button>
            </form>""".formatted(html(who.name().orElse(who.subject())), html(String.join(", ", who.groups())),
                csrf, log, csrf));
    }

    private static String usagePage(UsageReport usage) {
        StringBuilder rows = new StringBuilder();
        for (UsageReport.CallerUsage c : usage.callers()) {
            rows.append("<tr><td>").append(html(NAMES.getOrDefault(c.caller(), c.caller().subject())))
                .append("</td><td>").append(c.calls()).append("</td><td>").append(c.inputTokens())
                .append("</td><td>").append(c.outputTokens()).append("</td></tr>");
        }
        return page("Usage per user", """
            <table><tr><th>User</th><th>Model calls</th><th>Tokens in</th><th>Tokens out</th></tr>%s</table>
            <p><a href="/">back</a></p>""".formatted(rows));
    }

    private static String page(String title, String body) {
        return """
            <!doctype html><html><head><meta charset="utf-8"><title>%s</title><style>
              body { font: 16px/1.5 system-ui, sans-serif; max-width: 46rem; margin: 2rem auto; padding: 0 1rem; }
              .you { font-weight: 600; margin-bottom: 0 } .ai { margin-top: .2rem; color: #333 }
              .inline { display: inline } .button { padding: .4rem .8rem; border: 1px solid #888; text-decoration: none }
              td, th { padding: .2rem .8rem; text-align: left }
            </style></head><body><h1>%s</h1>%s</body></html>""".formatted(title, title, body);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    /** This browser session's turns, for display (the model's memory is CafeAI's, per user). */
    @SuppressWarnings("unchecked")
    private static List<String[]> transcript(Session session) {
        Object turns = session.get("transcript");
        if (turns == null) {
            turns = new ArrayList<String[]>();
            session.set("transcript", turns);
        }
        return (List<String[]>) turns;
    }

    private static String describe(AuditEvent event) {
        String who = event.caller() == null ? "anonymous" : NAMES.getOrDefault(event.caller(), event.caller().subject());
        return switch (event) {
            case AuditEvent.ModelCall m -> who + " on " + m.route() + ": " + m.model() + ", "
                + m.inputTokens() + " tokens in, " + m.outputTokens() + " out";
            case AuditEvent.GuardrailFlag g -> who + " on " + g.route() + ": guardrail " + g.guardrail()
                + " " + g.action() + " the " + g.stage();
        };
    }

    private static String claim(String json, String name) {
        var m = java.util.regex.Pattern.compile("\"" + name + "\"\\s*:\\s*(\"[^\"]*\"|\\[[^]]*])").matcher(json);
        return m.find() ? m.group(1).replace("\"", "") : "?";
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
