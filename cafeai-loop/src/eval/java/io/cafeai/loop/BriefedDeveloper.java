package io.cafeai.loop;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The developer in the evals: a model briefed with the developer's own recorded answers
 * ({@code eval/petclinic-developer.md}), because a live agent never asks the same questions in the
 * same order twice. The yes/no gates are fixed rules, so every run is let through the same doors:
 * reading, yes; committing and signing off, yes (on the eval's own checkout); open questions, yes.
 * After {@code maxTurns} answers it quits, so a run can't go on forever.
 */
final class BriefedDeveloper implements Developer {

    private final ChatModel model;
    private final String brief;
    private final int maxTurns;
    private final List<ChatMessage> history = new ArrayList<>();
    private final StringBuilder said = new StringBuilder();
    private final List<String> transcript = new ArrayList<>();
    private int turns;

    BriefedDeveloper(ChatModel model, int maxTurns) {
        this.model = model;
        this.brief = brief();
        this.maxTurns = maxTurns;
    }

    static String brief() {
        try (InputStream in = BriefedDeveloper.class.getResourceAsStream("eval/petclinic-developer.md")) {
            return new String(Objects.requireNonNull(in, "the brief is missing").readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void say(String text) {
        transcript.add("cafeai: " + text);
        if (!said.isEmpty()) said.append("\n\n");
        said.append(text);
    }

    @Override
    public String ask(String prompt) {
        transcript.add("cafeai asks: " + prompt);
        String answer;
        if (prompt.startsWith("The default branch") || prompt.startsWith("Its default branch")) {
            answer = "main";
        } else if (prompt.equals(">") && ++turns > maxTurns) {
            answer = "/quit";
        } else {
            answer = answer(prompt.equals(">") ? "" : prompt);
        }
        transcript.add("developer: " + answer);
        return answer;
    }

    @Override
    public boolean confirm(String question) {
        transcript.add("cafeai asks: " + question);
        boolean yes = !question.contains("Create a git repository");     // the checkout is already one
        transcript.add("developer: " + (yes ? "y" : "n"));
        said.setLength(0);
        return yes;
    }

    /** The brief's answer to what the agent said since the last answer, and {@code prompt}. */
    private String answer(String prompt) {
        String message = said + (prompt.isEmpty() ? "" : (said.isEmpty() ? "" : "\n\n") + prompt);
        said.setLength(0);
        history.add(UserMessage.from(message.isBlank() ? "(nothing)" : message));
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from(brief));
        messages.addAll(history);
        AiMessage reply = model.chat(ChatRequest.builder().messages(messages).build()).aiMessage();
        history.add(reply);
        String text = reply.text() == null ? "" : reply.text().strip();
        return text.contains("/quit") ? "/quit" : text;     // the brief's way out, when only the real developer could go on
    }

    /** What was said and answered, in order. */
    String transcript() {
        return String.join("\n\n", transcript);
    }
}
