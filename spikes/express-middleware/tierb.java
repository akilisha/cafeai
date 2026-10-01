///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//DEPS org.graalvm.polyglot:polyglot:25.4.4.1.1
//DEPS org.graalvm.js:js-language:25.4.4.1.1
//DEPS org.graalvm.truffle:truffle-runtime:25.4.4.1.1
import org.graalvm.polyglot.*;
import org.graalvm.polyglot.io.IOAccess;
class tierb {
    public static void main(String[] a) {
        String dir = System.getProperty("user.dir");
        String[][] cases = {
            {"express-basic-auth", "require('express-basic-auth')({ users: { admin: 'secret' } })",
             "Authorization", "Basic YWRtaW46c2VjcmV0"},
            {"morgan", "require('morgan')('tiny')", "User-Agent", "curl"},
        };
        for (String[] c : cases) {
            try (Context ctx = Context.newBuilder("js").allowExperimentalOptions(true).allowIO(IOAccess.ALL)
                    .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT).allowArrayAccess(true).build())
                    .option("engine.WarnInterpreterOnly", "false")
                    .option("js.commonjs-require", "true").option("js.commonjs-core-modules-replacements", "buffer:buffer/,assert:assert/").option("js.commonjs-require-cwd", dir).build()) {
                Value run = ctx.eval("js", "require('./adapter.js').run");
                Value mw = ctx.eval("js", c[1]);
                Value out = run.execute(mw, "GET", "/json", new String[] {c[2]}, new String[] {c[3]});
                System.out.println(c[0] + ": " + ctx.eval("js", "JSON.stringify").execute(out));
            } catch (PolyglotException e) {
                System.out.println(c[0] + ": FAILED -- " + e.getMessage());
            }
        }
    }
}
