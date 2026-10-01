///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS com.akilisha.oss:cafeai-core:0.5.1
//DEPS org.slf4j:slf4j-nop:2.0.19

import io.cafeai.core.CafeAI;
import java.util.Map;

class cafeai {
    public static void main(String[] args) {
        var app = CafeAI.create();
        app.get("/json", (req, res, next) -> res.json(Map.of("message", "Hello, World!")));
        // Proves the setup: is this handler running on a virtual thread?
        app.get("/thread", (req, res, next) ->
            res.json(Map.of("virtual", Thread.currentThread().isVirtual())));
        // Simulates a blocking downstream call (JDBC, HTTP) of the given length.
        app.get("/block", (req, res, next) -> {
            try { Thread.sleep(Long.parseLong(req.query("ms", "100"))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            res.json(Map.of("slept", true));
        });
        app.listen(8080, () -> System.out.println("ready"));
    }
}
