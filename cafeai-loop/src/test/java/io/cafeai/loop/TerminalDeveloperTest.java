package io.cafeai.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** ROADMAP-03, 3.1 and 3.6: at the terminal, only a yes is a yes: anything else, or nothing, is a no. */
@DisplayName("The developer at a terminal: only a yes is a yes")
class TerminalDeveloperTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private TerminalDeveloper typing(String typed) {
        return new TerminalDeveloper(new BufferedReader(new StringReader(typed)),
                new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @ParameterizedTest @ValueSource(strings = {"y\n", "Y\n", "yes\n", " YES \n"})
    @DisplayName("y or yes, in any case, is a yes")
    void yes(String typed) {
        assertThat(typing(typed).confirm("Commit it?")).isTrue();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Commit it? [y/N]");
    }

    @ParameterizedTest @ValueSource(strings = {"\n", "n\n", "no\n", "yep\n", "sure\n", "ok\n", ""})
    @DisplayName("anything else, Enter, or the end of input is a no")
    void no(String typed) {
        assertThat(typing(typed).confirm("Commit it?")).isFalse();
    }

    @Test @DisplayName("a question shows its prompt and returns the line typed; the end of input is null")
    void ask() {
        var developer = typing("trunk\n");
        assertThat(developer.ask("Its default branch? [main]")).isEqualTo("trunk");
        assertThat(developer.ask(">")).isNull();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Its default branch? [main]");
    }
}
