///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS io.javalin:javalin:7.2.3
//DEPS com.fasterxml.jackson.core:jackson-databind:2.22.2
//DEPS org.slf4j:slf4j-nop:2.0.19

import io.javalin.Javalin;

import java.util.Map;

/** The same routes on Javalin (Jetty), with Javalin's own virtual-thread switch on. */
class javalin {
    public static void main(String[] args) {
        Javalin.create(config -> {
            config.concurrency.useVirtualThreads = true;
            config.routes.get("/json", ctx -> ctx.json(Map.of("message", "Hello, World!")));
            config.routes.get("/thread", ctx -> ctx.json(Map.of("virtual", Thread.currentThread().isVirtual())));
            // Simulates a blocking downstream call (JDBC, HTTP) of the given length.
            config.routes.get("/block", ctx -> {
                Thread.sleep(Long.parseLong(ctx.queryParamAsClass("ms", String.class).getOrDefault("100")));
                ctx.json(Map.of("slept", true));
            });
        }).start(8080);
    }
}
