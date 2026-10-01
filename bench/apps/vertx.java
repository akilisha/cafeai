///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS io.vertx:vertx-core:5.2.0
//DEPS io.vertx:vertx-web:5.2.0
//DEPS org.slf4j:slf4j-nop:2.0.19

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VerticleBase;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

/**
 * The same routes on Vert.x, the way Vert.x is meant to run: non-blocking handlers
 * on event loops, one verticle instance per core. The 100 ms wait is
 * vertx.setTimer -- sleeping here would stall an event loop.
 */
class vertx {
    public static void main(String[] args) {
        Vertx.vertx().deployVerticle(Server::new,
                new DeploymentOptions().setInstances(Runtime.getRuntime().availableProcessors()));
    }

    public static class Server extends VerticleBase {
        @Override
        public Future<?> start() {
            Router router = Router.router(vertx);
            router.get("/json").handler(ctx -> ctx.json(new JsonObject().put("message", "Hello, World!")));
            router.get("/thread").handler(ctx ->
                    ctx.json(new JsonObject().put("virtual", Thread.currentThread().isVirtual())));
            router.get("/block").handler(ctx -> {
                long ms = Long.parseLong(ctx.request().getParam("ms", "100"));
                vertx.setTimer(ms, id -> ctx.json(new JsonObject().put("slept", true)));
            });
            return vertx.createHttpServer().requestHandler(router).listen(8080);
        }
    }
}
