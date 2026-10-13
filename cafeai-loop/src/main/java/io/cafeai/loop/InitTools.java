package io.cafeai.loop;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code init}'s own tool: finishing (ROADMAP-03, 3.4 and 3.5). It finishes only when the design
 * spec has every section it needs, and, if questions are still open, when the developer has
 * agreed to move on with them open. The code holds these lines, whatever the model does.
 */
public final class InitTools {

    private final Path project;
    private final boolean existingCodebase;
    private final Developer developer;
    private volatile boolean finished;

    public InitTools(Path project, boolean existingCodebase, Developer developer) {
        this.project = project;
        this.existingCodebase = existingCodebase;
        this.developer = developer;
    }

    @Tool("Finishes init, once DESIGN_SPEC.md is written with every section. If questions are still open, the"
            + " developer is asked whether to move on with them open.")
    public String finish(@P("what was done, in a sentence") String summary) {
        Path spec = project.resolve("DESIGN_SPEC.md");
        if (!Files.isRegularFile(spec)) return "Not finished: there is no DESIGN_SPEC.md yet.";
        String text;
        try {
            text = Files.readString(spec, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read DESIGN_SPEC.md", e);
        }
        SpecCheck check = SpecCheck.of(text, existingCodebase);
        if (!check.missing().isEmpty()) {
            return "Not finished: DESIGN_SPEC.md lacks these sections: " + String.join(", ", check.missing()) + ".";
        }
        if (!check.openQuestions().isEmpty()) {
            var b = new StringBuilder("These questions are still open in DESIGN_SPEC.md:\n");
            check.openQuestions().forEach(q -> b.append("  - ").append(q).append('\n'));
            b.append("Move on with them open?");
            if (!developer.confirm(b.toString())) {
                return "Not finished: the developer wants to settle these open questions first: "
                        + String.join("; ", check.openQuestions());
            }
        }
        finished = true;
        return "Finished.";
    }

    /** Whether {@code finish} has succeeded. */
    public boolean finished() {
        return finished;
    }
}
