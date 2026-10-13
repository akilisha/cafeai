package io.cafeai.loop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Locale;

/** The developer at a terminal: lines in, lines out. */
public final class TerminalDeveloper implements Developer {

    private final BufferedReader in;
    private final PrintStream out;

    public TerminalDeveloper(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    @Override
    public void say(String text) {
        out.println(text);
        out.println();
    }

    @Override
    public String ask(String prompt) {
        out.print(prompt + " ");
        out.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the developer's answer", e);
        }
    }

    @Override
    public boolean confirm(String question) {
        String answer = ask(question + " [y/N]");
        if (answer == null) return false;
        String a = answer.strip().toLowerCase(Locale.ROOT);
        return a.equals("y") || a.equals("yes");
    }
}
