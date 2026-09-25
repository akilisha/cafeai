package io.cafeai.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cafeai.core.CafeAI;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.session.Session;
import io.cafeai.core.session.SessionStore;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * RedisSessionExample -- a hand-rolled distributed HTTP session store.
 *
 * <p>{@code cafeai-session}'s {@code SqliteSessionStore} is single-instance
 * only (see its Javadoc). CafeAI does not ship a distributed
 * {@code SessionStore} -- this example shows how to write one yourself, in
 * about 40 lines, for a multi-instance deployment where a session cookie
 * must resolve to the same session no matter which instance handles the
 * request.
 *
 * <h2>Not to be confused with RedisMemoryExample</h2>
 * {@code RedisMemoryExample} demonstrates {@code MemoryStrategy.redis(...)} --
 * a <em>maintained</em> CafeAI rung for AI conversation history. This is a
 * different concept (HTTP session: auth state, cart, flash messages) and a
 * different arrangement: there is no maintained {@code cafeai-session} Redis
 * rung, only this illustration. Contrast the key prefixes:
 * {@code RedisMemoryStrategy} uses {@code cafeai:session:<id>}; this example
 * deliberately uses {@code cafeai:httpsession:<id>} so the two stay visibly
 * distinct even in {@code redis-cli keys}.
 *
 * <h2>Prerequisites</h2>
 * Start Redis before running:
 * <pre>
 *   docker run -d -p 6379:6379 redis:7-alpine
 * </pre>
 *
 * <h2>Running</h2>
 * <pre>
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.RedisSessionExample
 * </pre>
 *
 * <h2>Proving the session is shared</h2>
 * <pre>
 *   # Log in -- note the Set-Cookie value in the response
 *   curl -i -X POST http://localhost:8080/login \
 *        -H "Content-Type: application/json" -d '{"name": "Alex"}'
 *
 *   # Reuse the cookie -- works even if a different instance handled it,
 *   # because the session lives in Redis, not JVM heap
 *   curl http://localhost:8080/whoami -H "Cookie: cafeai.sid=&lt;value from Set-Cookie&gt;"
 * </pre>
 *
 * <h2>Inspecting sessions directly</h2>
 * <pre>
 *   redis-cli keys "cafeai:httpsession:*"
 *   redis-cli get "cafeai:httpsession:&lt;id&gt;"
 * </pre>
 */
public class RedisSessionExample {

    public static void main(String[] args) {
        var app = CafeAI.create();

        app.filter(CafeAI.json());
        app.filter(Middleware.session(new RedisSessionStore("localhost", 6379)));

        app.get("/health", (req, res, next) -> res.json(Map.of("status", "ok")));

        app.post("/login", (req, res, next) -> {
            String name = req.body("name");
            if (name == null || name.isBlank()) {
                res.status(400).json(Map.of("error", "name field required"));
                return;
            }
            req.session().set("name", name);
            res.json(Map.of("status", "ok", "sessionId", req.session().id()));
        });

        app.get("/whoami", (req, res, next) -> {
            String name = req.session().get("name", String.class);
            res.json(Map.of("name", name != null ? name : "anonymous"));
        });

        app.post("/logout", (req, res, next) -> {
            req.session().invalidate();
            res.json(Map.of("status", "ok"));
        });

        app.listen(8080, () -> System.out.println("""
            ☕ RedisSessionExample running on http://localhost:8080

               GET  /health   → health check
               POST /login    → {"name": "..."} sets the session
               GET  /whoami   → reads it back
               POST /logout   → invalidates it

            Sessions live in Redis at localhost:6379 -- shared across any
            number of CafeAI instances pointed at the same Redis. Try:

              curl -i -X POST http://localhost:8080/login \\
                   -H "Content-Type: application/json" -d '{"name":"Alex"}'

              # copy the cafeai.sid value from Set-Cookie, then:
              curl http://localhost:8080/whoami -H "Cookie: cafeai.sid=<value>"

            Inspect sessions:
              redis-cli keys "cafeai:httpsession:*"

            Press Ctrl+C to stop.
            """));
    }

    /**
     * A hand-rolled Redis-backed {@link SessionStore} -- about 40 lines, the
     * pattern to copy for any shared store a multi-instance deployment
     * needs. Uses Lettuce's synchronous API (virtual threads park while the
     * call completes -- no reactive ceremony needed, the same choice
     * {@code RedisMemoryStrategy} makes).
     */
    static final class RedisSessionStore implements SessionStore {

        private static final String KEY_PREFIX = "cafeai:httpsession:";
        private static final Duration TTL = Duration.ofHours(24);

        private static final ObjectMapper MAPPER = new ObjectMapper();

        private record SessionRecord(Map<String, Object> attributes, long createdAt, long lastAccessedAt) {}

        private final RedisCommands<String, String> commands;

        RedisSessionStore(String host, int port) {
            RedisClient client = RedisClient.create("redis://" + host + ":" + port);
            StatefulRedisConnection<String, String> connection = client.connect();
            this.commands = connection.sync();
        }

        @Override
        public Session create() {
            return new Session(UUID.randomUUID().toString());
        }

        @Override
        public Session load(String sessionId) {
            try {
                String json = commands.get(KEY_PREFIX + sessionId);
                if (json == null) return null;
                SessionRecord record = MAPPER.readValue(json, SessionRecord.class);
                return new Session(sessionId, record.attributes(),
                    Instant.ofEpochMilli(record.createdAt()),
                    Instant.ofEpochMilli(record.lastAccessedAt()));
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void save(Session session) {
            try {
                SessionRecord record = new SessionRecord(session.attributes(),
                    session.createdAt().toEpochMilli(), session.lastAccessedAt().toEpochMilli());
                commands.set(KEY_PREFIX + session.id(), MAPPER.writeValueAsString(record),
                    SetArgs.Builder.ex(TTL.getSeconds()));
            } catch (Exception e) {
                throw new RuntimeException("Cannot save session: " + session.id(), e);
            }
        }

        @Override
        public void destroy(String sessionId) {
            commands.del(KEY_PREFIX + sessionId);
        }

        @Override
        public boolean exists(String sessionId) {
            Long count = commands.exists(KEY_PREFIX + sessionId);
            return count != null && count > 0;
        }
    }
}
