///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS org.springframework.boot:spring-boot-starter-webflux:4.1.1
//JAVA_OPTIONS -Dserver.port=8080 -Dlogging.level.root=WARN

package bench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * The same routes on Spring WebFlux (Netty). The 100 ms wait is Mono.delay, the
 * reactive way to wait without holding a thread -- Thread.sleep here would stall
 * an event-loop thread, a bug no WebFlux code would ship.
 */
@SpringBootApplication
@RestController
public class webflux {
    public static void main(String[] args) {
        SpringApplication.run(webflux.class, args);
    }

    @GetMapping("/json")
    Mono<Map<String, String>> json() {
        return Mono.just(Map.of("message", "Hello, World!"));
    }

    @GetMapping("/thread")
    Mono<Map<String, Boolean>> thread() {
        return Mono.just(Map.of("virtual", Thread.currentThread().isVirtual()));
    }

    @GetMapping("/block")
    Mono<Map<String, Boolean>> block(@RequestParam(defaultValue = "100") long ms) {
        return Mono.delay(Duration.ofMillis(ms)).map(tick -> Map.of("slept", true));
    }
}
