///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS com.akilisha.oss:cafeai-core:0.5.0
//DEPS org.graalvm.polyglot:polyglot:25.4.4.1.1
//DEPS org.graalvm.js:js-language:25.4.4.1.1
//DEPS org.graalvm.truffle:truffle-runtime:25.4.4.1.1
//DEPS org.slf4j:slf4j-nop:2.0.19

import io.cafeai.core.CafeAI;
import io.cafeai.core.middleware.Middleware;
import io.cafeai.core.middleware.Next;
import io.cafeai.core.routing.Request;
import io.cafeai.core.routing.Response;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spike: unmodified npm middleware (helmet, cors) running inside a CafeAI app.
 *
 *   jbang spike.java [none|java|npm] [port]
 *
 *   none -- no middleware (baseline)
 *   java -- CafeAI's own Middleware.cors()
 *   npm  -- helmet() and cors({...}) from node_modules, through GraalJS
 */
class spike {
    // An allow-list (not a fixed string), so cors must read the request's Origin.
    static final String CORS_OPTIONS = "{ origin: ['https://app.example.com'], credentials: true, maxAge: 600 }";

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "npm";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        Path dir = Path.of(System.getProperty("user.dir"));

        var app = CafeAI.create();
        switch (mode) {
            case "none" -> { }
            case "java" -> app.filter(Middleware.cors());
            case "npm" -> {
                app.filter(ExpressMiddleware.of(dir, "helmet", "{}"));
                app.filter(ExpressMiddleware.of(dir, "cors", CORS_OPTIONS));
            }
            default -> throw new IllegalArgumentException("mode: none | java | npm");
        }
        app.get("/json", (req, res, next) -> res.json(Map.of("message", "Hello, World!")));
        app.listen(port, () -> System.out.println("spike (" + mode + ") on " + port));
    }

    /**
     * Runs one Express middleware from node_modules as a CafeAI middleware.
     *
     * A GraalJS context may be used by one thread at a time, so each middleware
     * keeps a pool of contexts (one engine, so parsed code is shared); a request
     * borrows one, runs the middleware, and returns it.
     */
    static final class ExpressMiddleware implements Middleware {
        private final Engine engine = Engine.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        private final Path dir;
        private final String module;
        private final String options;
        private final Source adapter;
        private final int max = Runtime.getRuntime().availableProcessors() * 2;
        private final AtomicInteger created = new AtomicInteger();
        private final BlockingQueue<Js> pool = new ArrayBlockingQueue<>(max);

        private record Js(Context context, Value run, Value middleware) { }

        private ExpressMiddleware(Path dir, String module, String options) throws IOException {
            this.dir = dir;
            this.module = module;
            this.options = options;
            this.adapter = Source.newBuilder("js", Files.readString(dir.resolve("adapter.js")), "adapter.js").build();
            pool.add(create());   // fail at startup, not on the first request
            System.out.println(module + ": GraalJS engine = " + engine.getImplementationName());
        }

        static Middleware of(Path dir, String module, String options) throws IOException {
            return new ExpressMiddleware(dir, module, options);
        }

        private Js create() {
            Context context = Context.newBuilder("js")
                    .engine(engine)
                    .allowExperimentalOptions(true)
                    // JS may index the header arrays we pass in, nothing more. Without
                    // this the arrays read as empty and every request looks headerless.
                    .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT).allowArrayAccess(true).build())
                    .allowIO(IOAccess.ALL)
                    .option("js.commonjs-require", "true")
                    .option("js.commonjs-require-cwd", dir.toString())
                    .build();
            context.eval(adapter);
            Value run = context.eval("js", "require('./adapter.js').run");
            Value middleware = context.eval("js", "require('" + module + "')(" + options + ")");
            created.incrementAndGet();
            return new Js(context, run, middleware);
        }

        private Js borrow() throws InterruptedException {
            Js js = pool.poll();
            if (js != null) return js;
            if (created.get() < max) {
                synchronized (this) {
                    if (created.get() < max) return create();
                }
            }
            return pool.take();
        }

        @Override
        public void handle(Request req, Response res, Next next) {
            Map<String, String> headers = req.headers();
            String[] names = headers.keySet().toArray(String[]::new);
            String[] values = new String[names.length];
            for (int i = 0; i < names.length; i++) values[i] = headers.get(names[i]);

            // Copy everything out of the context before handing it back.
            String action, body, error;
            int status;
            List<String[]> outHeaders = new ArrayList<>();
            Js js;
            try {
                js = borrow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            try {
                Value out = js.run().execute(js.middleware(), req.method(), req.originalUrl(), names, values);
                action = out.getMember("action").asString();
                status = out.getMember("status").asInt();
                body = out.hasMember("body") ? out.getMember("body").asString() : null;
                error = out.hasMember("error") ? out.getMember("error").asString() : null;
                Value hs = out.getMember("headers");
                for (long i = 0; i < hs.getArraySize(); i++) {
                    Value h = hs.getArrayElement(i);
                    outHeaders.add(new String[] { h.getArrayElement(0).asString(), h.getArrayElement(1).asString() });
                }
            } finally {
                pool.offer(js);   // never full: at most max contexts exist
            }

            for (String[] h : outHeaders) res.append(h[0], h[1]);
            switch (action) {
                case "next" -> next.run();
                case "end" -> { res.status(status); res.send(body == null ? "" : body); }
                case "error" -> { res.status(500); res.send(module + " failed: " + error); }
                default -> { res.status(500); res.send(module + " did not finish synchronously (" + action + ")"); }
            }
        }
    }
}
