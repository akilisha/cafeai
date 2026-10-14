package io.cafeai.loop;

import io.cafeai.cli.CafeLogin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * The {@code cafeai} command with the development loop in it: the loop's commands, and
 * {@code login}, {@code logout} and {@code status}, handed to {@code cafeai-cli}
 * ({@code DESIGN_SPEC.md}, Where it sits).
 */
public final class Loop {

    /** The loop's commands, what each is for, and where it is planned while it isn't built (null once it is). */
    static final Map<String, String[]> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("init",   new String[] {"start a product: its design spec", null});
        COMMANDS.put("design", new String[] {"change the design spec", "ROADMAP-06"});
        COMMANDS.put("plan",   new String[] {"roadmaps and milestones from the design spec", null});
        COMMANDS.put("brew",   new String[] {"build from the roadmap, with evidence", null});
        COMMANDS.put("run",    new String[] {"run the product, or the platform's own command", "ROADMAP-08"});
        COMMANDS.put("review", new String[] {"bring back what is unfinished, stuck or unanswered", "ROADMAP-07"});
        COMMANDS.put("loop",   new String[] {"open the web app", "ROADMAP-05"});
    }

    private final PrintStream out;
    private final PrintStream err;
    private final Consumer<String[]> others;
    private final Map<String, IntSupplier> built;

    Loop(PrintStream out, PrintStream err, Consumer<String[]> others, Map<String, IntSupplier> built) {
        this.out = out;
        this.err = err;
        this.others = others;
        this.built = built;
    }

    public static void main(String[] args) {
        Path here = Path.of("").toAbsolutePath();
        var developer = new TerminalDeveloper(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        Map<String, IntSupplier> built = Map.of(
                "init", () -> new InitCommand(here, developer, settings -> Models.forCommand(settings, "init")).run(),
                "plan", () -> new PlanCommand(here, developer, settings -> Models.forCommand(settings, "plan")).run(),
                "brew", () -> new BrewCommand(here, developer, settings -> Models.forCommand(settings, "brew")).run());
        int status = new Loop(System.out, System.err, CafeLogin::main, built).run(args);
        System.exit(status);
    }

    /** Runs one command; returns the exit status. Commands that aren't the loop's go to {@code cafeai-cli}. */
    int run(String... args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("-h") || args[0].equals("--help")) {
            help(out);
            return 0;
        }
        String[] command = COMMANDS.get(args[0]);
        if (command == null) {
            others.accept(args);
            return 0;
        }
        IntSupplier runs = built.get(args[0]);
        if (runs != null) return runs.getAsInt();
        err.println("`cafeai " + args[0] + "` isn't built yet: it is planned in cafeai-loop's " + command[1] + ".");
        return 1;
    }

    private static void help(PrintStream to) {
        to.println("The development loop:");
        COMMANDS.forEach((name, about) -> to.printf("  cafeai %-8s %s%n", name, about[0]));
        to.println();
        CafeLogin.help(to);
    }
}
