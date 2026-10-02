package io.cafeai.dev;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs an app and reloads it whenever its source changes: the changed code is compiled
 * in this JVM, the running app is stopped, and {@code main} runs again from a fresh
 * class loader. Libraries stay loaded and warm, so a reload takes a few hundred
 * milliseconds. See {@link #LIMITATIONS} for what a reload does not handle.
 *
 * <p>{@link CafeDev} is the command line; this class is the engine, usable directly
 * (and in tests).
 */
public final class DevServer implements AutoCloseable {

    /** What a reload does not handle. Printed every time cafeai-dev starts. */
    public static final String LIMITATIONS = """
            What a reload does NOT handle -- read this once:
              * Threads and executors your app starts itself (not through CafeAI) keep
                running after a reload, still running the OLD code. Stop them yourself,
                or restart cafeai-dev. After each reload cafeai-dev names any it finds.
              * Static state, caches and open connections held by the old code are not
                closed -- only dropped. An external resource (a socket, a file lock, a
                database pool) stays open until it is garbage-collected.
              * A change to dependencies (//DEPS lines, build.gradle, pom.xml) needs a
                full restart of cafeai-dev. Only your own source is recompiled.
              * Development only. Never ship cafeai-dev or run it in production.""";

    private static final String P = "[cafeai-dev] ";

    private final String target;
    private final List<Path> sourceDirs;
    private final Path scriptFile;          // single-file mode; null for a project
    private final String mainClass;         // project mode; null for a script (found after compiling)
    private final List<Path> resourceDirs;
    private final List<Path> extraJars;     // a script's //DEPS
    private final List<String> deps;        // a script's //DEPS lines at start
    private final String[] args;
    private final PrintStream out;
    private final JavaCompiler javac;
    private final ScheduledExecutorService checks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cafeai-dev-check");
        t.setDaemon(true);
        return t;
    });

    private WatchService watch;
    private Thread watcher;
    private int generation;
    private AppClassLoader current;
    private Thread currentMain;

    private DevServer(String target, List<Path> sourceDirs, Path scriptFile, String mainClass,
                      List<Path> resourceDirs, List<Path> extraJars, List<String> deps,
                      String[] args, PrintStream out) {
        this.target = target;
        this.sourceDirs = sourceDirs;
        this.scriptFile = scriptFile;
        this.mainClass = mainClass;
        this.resourceDirs = resourceDirs;
        this.extraJars = extraJars;
        this.deps = deps;
        this.args = args;
        this.out = out;
        this.javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new IllegalStateException("cafeai-dev needs a JDK, not a JRE: no Java compiler is available");
        }
    }

    /**
     * A project: every {@code .java} file under {@code sourceDirs} is the app, compiled
     * against this JVM's classpath; {@code mainClass} is run.
     */
    public static DevServer project(String mainClass, List<Path> sourceDirs, List<Path> resourceDirs,
                                    String[] args, PrintStream out) {
        List<Path> sources = sourceDirs.stream().map(Path::toAbsolutePath).filter(Files::isDirectory).toList();
        if (sources.isEmpty()) throw new IllegalArgumentException("No source directory found among " + sourceDirs);
        List<Path> resources = resourceDirs.stream().map(Path::toAbsolutePath).filter(Files::isDirectory).toList();
        return new DevServer(Objects.requireNonNull(mainClass, "main class"), sources, null, mainClass,
                resources, List.of(), List.of(), args, out);
    }

    /**
     * A single-file JBang app: compiled with its {@code //DEPS}, which JBang resolves
     * ({@code jbang info classpath --deps-only}); its class with a {@code main} is run.
     */
    public static DevServer script(Path script, String[] args, PrintStream out) {
        Path file = script.toAbsolutePath();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("No such file: " + script);
        List<Path> jars = Jbang.depsClasspath(file);
        return new DevServer(file.getFileName().toString(), List.of(file.getParent()), file, null,
                List.of(), jars, Jbang.depsLines(file), args, out);
    }

    /** Builds and runs the app, then reloads it on every change until {@link #close()}. */
    public DevServer start() throws IOException {
        out.println(P + "Reloading " + target + ". Save a file and the app restarts with the new code.");
        out.println(LIMITATIONS.lines().map(l -> P + l).reduce((a, b) -> a + System.lineSeparator() + b).orElse(""));
        reload();
        watch = FileSystems.getDefault().newWatchService();
        for (Path dir : sourceDirs) registerTree(dir);
        for (Path dir : resourceDirs) registerTree(dir);
        watcher = new Thread(this::watchLoop, "cafeai-dev-watch");
        watcher.setDaemon(true);
        watcher.start();
        return this;
    }

    /** One reload: compile, and if it compiles, stop the running version and start this one. */
    synchronized void reload() {
        long t0 = System.nanoTime();
        int gen = ++generation;
        Path classes;
        try {
            classes = Files.createTempDirectory("cafeai-dev-" + gen + "-");
        } catch (IOException e) {
            out.println(P + "cannot create a build directory: " + e.getMessage());
            return;
        }
        if (!compile(classes)) {
            deleteTree(classes);
            out.println(P + (current == null ? "Fix the errors and save; the app starts once it compiles."
                                             : "The previous version keeps running. Fix the errors and save."));
            return;
        }
        if (scriptFile != null) warnIfDepsChanged();
        // Find main before stopping anything: a save that breaks it must not take down the running version.
        String main;
        try {
            main = mainClass != null ? mainClass : findScriptMain(classes);
        } catch (IllegalStateException e) {
            deleteTree(classes);
            out.println(P + e.getMessage() + (current == null ? "" : " -- the previous version keeps running."));
            return;
        }
        long t1 = System.nanoTime();

        AppClassLoader previous = current;
        Thread previousMain = currentMain;
        if (previous != null) {
            AppTracker.stopAppsOf(previous);
            if (previousMain != null && previousMain.isAlive()) previousMain.interrupt();
        }
        long t2 = System.nanoTime();

        AppClassLoader loader = new AppClassLoader(gen, classes, resourceDirs, extraJars, DevServer.class.getClassLoader());
        current = loader;
        currentMain = runMain(loader, main, gen);
        out.printf("%s%s version %d in %d ms (compile %d ms, stop %d ms)%n", P,
                previous == null ? "Started" : "Reloaded", gen,
                (t2 - t0) / 1_000_000, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);

        if (previous != null) {
            Path oldClasses = classesOf(previous);
            checks.schedule(() -> reportLeftovers(previous, gen - 1, oldClasses), 1500, TimeUnit.MILLISECONDS);
        }
    }

    private boolean compile(Path classes) {
        List<File> files = new ArrayList<>();
        if (scriptFile != null) {
            files.add(scriptFile.toFile());
        } else {
            for (Path dir : sourceDirs) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
                } catch (IOException e) {
                    out.println(P + "cannot read " + dir + ": " + e.getMessage());
                    return false;
                }
            }
        }
        if (files.isEmpty()) {
            out.println(P + "no .java files to compile");
            return false;
        }
        String classpath = System.getProperty("java.class.path")
                + extraJars.stream().map(j -> File.pathSeparator + j).reduce("", String::concat);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean ok = javac.getTask(null, fm, diagnostics,
                    List.of("-d", classes.toString(), "-cp", classpath, "-parameters", "-encoding", "UTF-8"),
                    null, fm.getJavaFileObjectsFromFiles(files)).call();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR && ok) continue;
                String where = d.getSource() == null ? "" : Path.of(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + ": ";
                out.println(P + d.getKind().toString().toLowerCase(Locale.ROOT) + ": " + where + d.getMessage(Locale.ROOT));
            }
            return ok;
        } catch (IOException e) {
            out.println(P + "compiler error: " + e.getMessage());
            return false;
        }
    }

    private Thread runMain(AppClassLoader loader, String main, int gen) {
        Thread t = new Thread(() -> {
            try {
                Method m = loader.loadClass(main).getDeclaredMethod("main", String[].class);
                m.setAccessible(true);
                m.invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                if (!(e.getCause() instanceof InterruptedException)) {
                    out.println(P + "version " + gen + "'s main threw:");
                    e.getCause().printStackTrace(out);
                }
            } catch (ReflectiveOperationException e) {
                out.println(P + "cannot run " + main + ".main: " + e);
            }
        }, "cafeai-dev-main-" + gen);
        t.setContextClassLoader(loader);
        t.start();
        return t;
    }

    /** After the old version is stopped, names what it left running: the first limitation, as it happens. */
    private void reportLeftovers(AppClassLoader old, int oldGen, Path oldClasses) {
        // A thread counts when it is still executing the old version's code -- one of its stack
        // frames is an app class. A framework thread that merely inherited the old version's
        // context class loader (Helidon starts some while the app starts) runs no app code.
        List<String> names = Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> e.getKey().isAlive())
                .filter(e -> java.util.Arrays.stream(e.getValue()).anyMatch(f -> isAppClass(oldClasses, f.getClassName())))
                .map(e -> e.getKey().getName())
                .sorted()
                .toList();
        if (!names.isEmpty()) {
            out.println(P + "WARNING: version " + oldGen + " left " + names.size() + " thread(s) running, still on the old code: "
                    + String.join(", ", names));
            out.println(P + "         reload stops CafeAI apps, not threads your app starts itself. Stop them yourself, or restart cafeai-dev.");
            return;   // the old classes are still in use: keep their directory
        }
        try {
            old.close();
        } catch (IOException ignored) { /* best effort */ }
        deleteTree(oldClasses);
    }

    private static boolean isAppClass(Path classes, String className) {
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        return Files.exists(classes.resolve(outer.replace('.', '/') + ".class"));
    }

    private void warnIfDepsChanged() {
        List<String> now = Jbang.depsLines(scriptFile);
        if (!now.equals(deps)) {
            out.println(P + "WARNING: the //DEPS lines changed. Restart cafeai-dev to use them -- "
                    + "this session still compiles and runs against the dependencies it started with.");
        }
    }

    private String findScriptMain(Path classes) {
        String base = scriptFile.getFileName().toString().replaceFirst("\\.java$", "");
        List<String> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            walk.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$"))
                .forEach(p -> candidates.add(classes.relativize(p).toString()
                        .replace(File.separatorChar, '.').replaceFirst("\\.class$", "")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read compiled classes: " + e.getMessage());
        }
        candidates.sort(Comparator.comparing((String c) -> !c.substring(c.lastIndexOf('.') + 1).equals(base)));
        try (AppClassLoader probe = new AppClassLoader(0, classes, List.of(), extraJars, DevServer.class.getClassLoader())) {
            for (String c : candidates) {
                try {
                    Method m = probe.loadClass(c).getDeclaredMethod("main", String[].class);
                    if (Modifier.isStatic(m.getModifiers())) return c;
                } catch (NoSuchMethodException | ClassNotFoundException | LinkageError ignored) { /* next */ }
            }
        } catch (IOException ignored) { /* closing the probe */ }
        throw new IllegalStateException("No class with a static main(String[]) in " + scriptFile.getFileName());
    }

    // -- watching -------------------------------------------------------------------------

    private void registerTree(Path dir) throws IOException {
        if (scriptFile != null) {
            dir.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                d.register(watch, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchLoop() {
        try {
            while (true) {
                WatchKey key = watch.take();
                boolean relevant = handle(key);
                // Debounce: an editor's save, or a "save all", is several events in a burst.
                WatchKey more;
                while ((more = watch.poll(120, TimeUnit.MILLISECONDS)) != null) relevant |= handle(more);
                if (relevant) reload();
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // closed
        }
    }

    private boolean handle(WatchKey key) {
        boolean relevant = false;
        Path dir = (Path) key.watchable();
        for (WatchEvent<?> event : key.pollEvents()) {
            if (!(event.context() instanceof Path name)) continue;
            Path changed = dir.resolve(name);
            if (scriptFile != null) {
                relevant |= changed.equals(scriptFile);
            } else {
                relevant = true;
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                    try {
                        registerTree(changed);
                    } catch (IOException ignored) { /* best effort */ }
                }
            }
        }
        key.reset();
        return relevant;
    }

    @Override
    public synchronized void close() {
        try {
            if (watch != null) watch.close();
        } catch (IOException ignored) { /* closing */ }
        if (current != null) {
            AppTracker.stopAppsOf(current);
            if (currentMain != null && currentMain.isAlive()) currentMain.interrupt();
        }
        checks.shutdownNow();
    }

    private static Path classesOf(AppClassLoader loader) {
        try {
            return Path.of(loader.getURLs()[0].toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) { /* temp files; best effort */ }
    }
}
