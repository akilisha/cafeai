package io.cafeai.loop;

import io.cafeai.cli.CafeLogin;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@code cafeai} command with the development loop in it: the loop's commands, and
 * {@code login}, {@code logout} and {@code status}, handed to {@code cafeai-cli}
 * ({@code DESIGN_SPEC.md}, Where it sits).
 */
public final class Loop {

    /** The loop's commands, what each is for, and where it is planned while it isn't built. */
    static final Map<String, String[]> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("init",   new String[] {"start a product: its design spec", "ROADMAP-03 phase 3"});
        COMMANDS.put("design", new String[] {"change the design spec", "ROADMAP-06"});
        COMMANDS.put("plan",   new String[] {"roadmaps and milestones from the design spec", "ROADMAP-03 phase 4"});
        COMMANDS.put("brew",   new String[] {"build from the roadmap, with evidence", "ROADMAP-05"});
        COMMANDS.put("run",    new String[] {"run the product, or the platform's own command", "ROADMAP-08"});
        COMMANDS.put("review", new String[] {"bring back what is unfinished, stuck or unanswered", "ROADMAP-07"});
        COMMANDS.put("loop",   new String[] {"open the web app", "ROADMAP-04"});
    }

    private final PrintStream out;
    private final PrintStream err;
    private final Consumer<String[]> others;

    Loop(PrintStream out, PrintStream err, Consumer<String[]> others) {
        this.out = out;
        this.err = err;
        this.others = others;
    }

    public static void main(String[] args) {
        int status = new Loop(System.out, System.err, CafeLogin::main).run(args);
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
