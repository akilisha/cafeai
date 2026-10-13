package io.cafeai.loop;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@code plan}'s own tool: finishing (ROADMAP-03, phase 4). It finishes only when the plan is in the
 * format of ARTIFACTS.md, leaves estimates to the team, and keeps every number it had before
 * ({@link PlanCheck}). The code holds these lines, whatever the model does.
 */
public final class PlanTools {

    private final Path project;
    private final Map<String, String> before;
    private volatile boolean finished;

    /** @param before the roadmap directory as it was when {@code plan} began ({@link PlanCheck#snapshot}) */
    public PlanTools(Path project, Map<String, String> before) {
        this.project = project;
        this.before = Map.copyOf(before);
    }

    @Tool("Finishes plan, once the roadmaps, their milestones and the index are written. If anything isn't in the"
            + " format, or a number planned before was changed, it says what, and plan isn't finished.")
    public String finish(@P("what was planned, in a sentence") String summary) {
        PlanCheck check = PlanCheck.of(before, PlanCheck.snapshot(project));
        if (!check.ok()) return "Not finished:\n- " + String.join("\n- ", check.problems());
        finished = true;
        return "Finished.";
    }

    /** Whether {@code finish} has succeeded. */
    public boolean finished() {
        return finished;
    }
}
