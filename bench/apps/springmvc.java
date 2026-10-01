///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS org.springframework.boot:spring-boot-starter-webmvc:4.1.1
//JAVA_OPTIONS -Dspring.threads.virtual.enabled=true -Dserver.port=8080 -Dlogging.level.root=WARN

package bench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The same two routes as cafeai.java, on Spring MVC with virtual threads (Spring's own switch). */
@SpringBootApplication
@RestController
public class springmvc {
    public static void main(String[] args) {
        SpringApplication.run(springmvc.class, args);
    }

    @GetMapping("/json")
    Map<String, String> json() {
        return Map.of("message", "Hello, World!");
    }

    /** Proves the setup: is this handler running on a virtual thread? */
    @GetMapping("/thread")
    Map<String, Boolean> thread() {
        return Map.of("virtual", Thread.currentThread().isVirtual());
    }

    // Simulates a blocking downstream call (JDBC, HTTP) of the given length.
    @GetMapping("/block")
    Map<String, Boolean> block(@RequestParam(defaultValue = "100") long ms) throws InterruptedException {
        Thread.sleep(ms);
        return Map.of("slept", true);
    }
}
