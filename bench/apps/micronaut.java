///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS io.micronaut.platform:micronaut-platform:5.2.1@pom
//DEPS io.micronaut:micronaut-http-server-netty
//DEPS io.micronaut:micronaut-inject-java
//DEPS io.micronaut.serde:micronaut-serde-jackson
//DEPS io.micronaut.serde:micronaut-serde-processor
//DEPS org.slf4j:slf4j-nop:2.0.19
//COMPILE_OPTIONS -proc:full
//JAVA_OPTIONS -Dmicronaut.server.port=8080

package bench;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.runtime.Micronaut;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.Map;

/**
 * The same routes on Micronaut (Netty). /json stays on the event loop, Micronaut's
 * default for a plain controller; /block runs on virtual threads with
 * Thread.sleep -- the same model as CafeAI -- via @ExecuteOn(VIRTUAL).
 */
public class micronaut {
    public static void main(String[] args) {
        Micronaut.run(micronaut.class, args);
    }

    @Controller
    public static class Routes {
        @Get("/json")
        public Map<String, String> json() {
            return Map.of("message", "Hello, World!");
        }

        @Get("/thread")
        @ExecuteOn(TaskExecutors.VIRTUAL)
        public Map<String, Boolean> thread() {
            return Map.of("virtual", Thread.currentThread().isVirtual());
        }

        // Simulates a blocking downstream call (JDBC, HTTP) of the given length.
        @Get("/block")
        @ExecuteOn(TaskExecutors.VIRTUAL)
        public Map<String, Boolean> block(@QueryValue(defaultValue = "100") long ms) throws InterruptedException {
            Thread.sleep(ms);
            return Map.of("slept", true);
        }
    }
}
