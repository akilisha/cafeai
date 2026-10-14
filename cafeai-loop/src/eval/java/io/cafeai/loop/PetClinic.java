package io.cafeai.loop;

import io.cafeai.core.internal.Cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The evals' fixture (DESIGN_SPEC.md, Testing the loop itself): Spring PetClinic, referenced, not
 * copied. A checkout is a fresh repository holding PetClinic's {@code main} at the base commit and
 * its history, and nothing else: no remote, no other branches (they move upstream, and a recording
 * replays only if every run sees the same). The run by hand's artifacts are read from the
 * developer's local clone, where they live on two branches.
 */
final class PetClinic {

    /** {@code main} at 2026-09-29, where the run by hand began. */
    static final String BASE = "500158f732419217507c7656904b8e6aa1bcc0d6";
    /** The run by hand's reviewed design spec, on {@code feature/design-spec}. */
    static final String HAND_SPEC = "aabc476";
    /** The run by hand's plan, on {@code feature/roadmaps}. */
    static final String HAND_PLAN = "400ff04";
    static final String UPSTREAM = "https://github.com/spring-projects/spring-petclinic";

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private PetClinic() {}

    /** The developer's local clone, if one is named ({@code -Peval.petclinic=<dir>}); it holds the run by hand. */
    static Optional<Path> localClone() {
        String dir = System.getProperty("eval.petclinic", "");
        return dir.isBlank() ? Optional.empty() : Optional.of(Path.of(dir));
    }

    /** A fresh checkout of PetClinic at the base commit, in {@code dir}: from the local clone if there is one, else upstream. */
    static Path checkout(Path dir) {
        String source = localClone().map(Path::toString).orElse(UPSTREAM);
        git(dir, "init", "-b", "main");
        git(dir, "fetch", "--no-tags", source, BASE);
        git(dir, "reset", "--hard", BASE);
        git(dir, "config", "user.name", "Eval Developer");
        git(dir, "config", "user.email", "eval.developer@example.invalid");
        git(dir, "config", "commit.gpgsign", "false");
        return dir;
    }

    /** The run by hand's design spec, from the local clone. */
    static String handSpec() {
        return show(HAND_SPEC, "DESIGN_SPEC.md");
    }

    /**
     * The spec {@code plan} starts from: the run by hand's, with the developer's answer to the conflict
     * the first live {@code plan} found on 2026-10-13 and the run by hand missed. The spec assumed every
     * existing visit is in the past, but the app accepts visits dated tomorrow or later, so a migration
     * marking them all {@code COMPLETED} would close future bookings.
     */
    static String planSpec() {
        String spec = handSpec();
        spec = once(spec, "and a status (see §7)", "and a status (see §6)");
        spec = once(spec, "- **Migrated visits are `COMPLETED`:** they are all in the past (the seed data is from 2013).\n", "");
        spec = once(spec, "\n## 7. Assumptions", "- **Migrated visits:** those dated before the migration become `COMPLETED`; those dated"
                + " that day or later\n  become `SCHEDULED`, at 09:00, with no vet until reception assigns one (decided 2026-10-13,\n"
                + "  when `cafeai plan` found that the app accepts visits dated in the future).\n\n## 7. Assumptions");
        return spec;
    }

    private static String once(String text, String from, String to) {
        if (text.indexOf(from) < 0 || text.indexOf(from) != text.lastIndexOf(from)) {
            throw new IllegalStateException("The spec correction no longer applies: '" + from + "'");
        }
        return text.replace(from, to);
    }

    /** The run by hand's plan, {@code docs/roadmap/}, from the local clone: each file's name and text. */
    static Map<String, String> handPlan() {
        Path clone = localClone().orElseThrow(PetClinic::noClone);
        var files = new TreeMap<String, String>();
        for (String name : git(clone, "ls-tree", "--name-only", HAND_PLAN, "docs/roadmap/").lines().toList()) {
            files.put(name.substring(name.lastIndexOf('/') + 1), show(HAND_PLAN, name));
        }
        return files;
    }

    private static String show(String commit, String path) {
        return git(localClone().orElseThrow(PetClinic::noClone), "show", commit + ":" + path);
    }

    private static IllegalStateException noClone() {
        return new IllegalStateException("The run by hand's artifacts are in the local PetClinic clone: name it with"
                + " -Peval.petclinic=<dir>");
    }

    static String git(Path dir, String... args) {
        var command = new ArrayList<>(List.of(Cli.find("git", System::getenv).map(Path::toString).orElse("git"),
                "-C", dir.toString()));
        command.addAll(List.of(args));
        Cli.Result result = Cli.run(command, TIMEOUT);
        if (!result.ok()) throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + result.err().strip());
        return result.out();
    }
}
