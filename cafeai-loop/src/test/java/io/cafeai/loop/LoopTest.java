package io.cafeai.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** ROADMAP-03, 1.1: one `cafeai` command, the loop's commands and cafeai-cli's together. */
@DisplayName("The cafeai command: the loop's commands, and cafeai-cli's")
class LoopTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final List<List<String>> handedOn = new ArrayList<>();
    private final List<String> ran = new ArrayList<>();
    private final Loop loop = new Loop(new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8), args -> handedOn.add(List.of(args)),
            Map.of("init", () -> { ran.add("init"); return 0; }));

    @Test @DisplayName("help lists the loop's commands, then login, logout and status")
    void help() {
        for (String[] args : List.of(new String[0], new String[] {"help"}, new String[] {"--help"}, new String[] {"-h"})) {
            out.reset();
            assertThat(loop.run(args)).isZero();
            String text = out.toString(StandardCharsets.UTF_8);
            for (String command : List.of("init", "design", "plan", "brew", "run", "review", "loop")) {
                assertThat(text).contains("cafeai " + command);
            }
            assertThat(text).contains("cafeai login").contains("cafeai logout").contains("cafeai status");
            assertThat(text.indexOf("cafeai init")).isLessThan(text.indexOf("cafeai login"));
        }
        assertThat(handedOn).isEmpty();
    }

    @Test @DisplayName("login, logout, status and anything else go to cafeai-cli, untouched")
    void handsOn() {
        loop.run("login", "claude");
        loop.run("status");
        loop.run("logout", "aws", "--profile", "work");
        loop.run("nonsense");
        assertThat(handedOn).containsExactly(List.of("login", "claude"), List.of("status"),
                List.of("logout", "aws", "--profile", "work"), List.of("nonsense"));
    }

    @Test @DisplayName("a loop command not built yet says where it is planned, and fails")
    void notBuiltYet() {
        assertThat(loop.run("design")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("`cafeai design` isn't built yet").contains("ROADMAP-06");
        assertThat(loop.run("review")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("ROADMAP-07");
        assertThat(handedOn).isEmpty();
        assertThat(ran).isEmpty();
    }

    @Test @DisplayName("a built command runs, and its status is the command's")
    void built() {
        assertThat(loop.run("init")).isZero();
        assertThat(ran).containsExactly("init");
        assertThat(handedOn).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
