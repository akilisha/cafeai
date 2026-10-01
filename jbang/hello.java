///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 23+
//DEPS com.akilisha.oss:cafeai-core:0.5.0
//DEPS org.slf4j:slf4j-simple:2.0.19

import io.cafeai.core.CafeAI;

import java.util.Map;

/**
 * A CafeAI web server in one file. No build, no project, no API key:
 *
 * <pre>
 *   jbang hello@akilisha/cafeai
 *   curl localhost:8080/hello/world
 * </pre>
 *
 * <p>Also the template behind {@code jbang init -t cafeai@akilisha/cafeai app.java},
 * which is why the class is not public: it compiles under any file name.
 */
class hello {
    public static void main(String[] args) {
        var app = CafeAI.create();
        app.filter(CafeAI.json());

        app.get("/hello/:name", (req, res, next) ->
            res.json(Map.of("hello", req.params("name"))));

        app.post("/echo", (req, res, next) ->
            res.json(Map.of("youSent", req.body("message"))));

        app.listen(8080, () -> System.out.println("""
            CafeAI is brewing on http://localhost:8080
              curl localhost:8080/hello/world
              curl -X POST localhost:8080/echo -H 'Content-Type: application/json' -d '{"message":"hi"}'
            """));
    }
}
