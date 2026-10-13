package io.cafeai.loop;

import io.cafeai.loop.tools.GitTools;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** What the commands' conversations share: the default branch asked for, and the turns until done. */
final class Conversation {

    private Conversation() {}

    /** The repository's default branch, as the developer names it; the remote's default (else {@code main}) offered. */
    static String defaultBranch(Path project, Developer developer) {
        String guess = GitTools.remoteDefault(project).orElse("main");
        String named = developer.ask("The default branch, where changes are merged? [" + guess + "]");
        return named == null || named.isBlank() ? guess : named.strip();
    }

    /**
     * Turns of the conversation, from {@code kickoff}: each reply said to the developer, then their
     * answer sent, until {@code finished} or the developer types {@code /quit} (or input ends).
     * Returns whether it finished.
     */
    static boolean converse(Agents.Session session, Developer developer, String kickoff, BooleanSupplier finished) {
        String message = kickoff;
        while (true) {
            developer.say(session.ask(message));
            if (finished.getAsBoolean()) return true;
            message = developer.ask(">");
            if (message == null || message.strip().equals("/quit")) return false;
        }
    }
}
