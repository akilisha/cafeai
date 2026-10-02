package io.cafeai.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The command line for {@link DevServer}: save a source file and the app restarts with
 * the new code.
 *
 * <pre>
 *   CafeDev app.java [app args...]                 a single-file JBang app
 *   CafeDev com.acme.App [options] [-- app args]    a project
 *     --src DIR         a source directory (default src/main/java; repeatable)
 *     --resources DIR   a resource directory (default src/main/resources; repeatable)
 * </pre>
 *
 * <p>Development only. What a reload does not handle is printed on every start
 * ({@link DevServer#LIMITATIONS}).
 */
public final class CafeDev {

    private CafeDev() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: CafeDev app.java [args...]  |  CafeDev com.acme.App [--src DIR] [--resources DIR] [-- args...]");
            System.exit(2);
        }
        DevServer server;
        if (args[0].endsWith(".java") && Files.isRegularFile(Path.of(args[0]))) {
            server = DevServer.script(Path.of(args[0]), Arrays.copyOfRange(args, 1, args.length), System.out);
        } else {
            List<Path> src = new ArrayList<>();
            List<Path> resources = new ArrayList<>();
            List<String> appArgs = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--src" -> src.add(Path.of(args[++i]));
                    case "--resources" -> resources.add(Path.of(args[++i]));
                    case "--" -> { appArgs.addAll(Arrays.asList(args).subList(i + 1, args.length)); i = args.length; }
                    default -> appArgs.add(args[i]);
                }
            }
            if (src.isEmpty()) src.add(Path.of("src/main/java"));
            if (resources.isEmpty()) resources.add(Path.of("src/main/resources"));
            server = DevServer.project(args[0], src, resources, appArgs.toArray(String[]::new), System.out);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "cafeai-dev-shutdown"));
        server.start();
        Thread.currentThread().join();   // reloads happen on the watcher thread
    }
}
