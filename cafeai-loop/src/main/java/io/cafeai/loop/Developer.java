package io.cafeai.loop;

/**
 * The person at the terminal: a command tells them things, asks them things, and asks their yes
 * before anything that is theirs to decide (reading their project, committing, signing off).
 */
public interface Developer {

    /** Tells the developer something. */
    void say(String text);

    /** Asks for a line of text; {@code null} when there is no more input. */
    String ask(String prompt);

    /** Asks a yes-or-no question; only an explicit yes is a yes. */
    boolean confirm(String question);
}
