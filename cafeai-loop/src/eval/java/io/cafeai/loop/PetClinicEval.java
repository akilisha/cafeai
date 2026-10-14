package io.cafeai.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.cafeai.core.ai.AiProvider;
import io.cafeai.core.internal.LangchainBridge;
import io.cafeai.core.internal.SavedKeys;
import io.cafeai.test.EvalReport;
import io.cafeai.test.Replay;
import io.cafeai.test.ReplayMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ROADMAP-03, phase 5: {@code init} and {@code plan} on Spring PetClinic, measured against the run
 * by hand. {@code init} starts from PetClinic's base commit; {@code plan} from the run by hand's
 * reviewed spec, so each is measured on its own. The developer is a model briefed with their
 * recorded answers ({@link BriefedDeveloper}).
 *
 * <p>Every model call goes through {@link Replay}: {@code -Peval.mode=replay} (the default) replays
 * the recordings, with no key and no cost (5.2); {@code record} calls the model and records it, which
 * is also how the evals run live against a model (5.3). {@code -Peval.model=provider:model} picks
 * the model; each has its own recordings, kept on this machine
 * ({@code ~/.cafeai/loop/evals/<model>/}), not in the repository: they hold PetClinic's code.
 *
 * <p>What each run produced, the conversation and the report are written to
 * {@code build/evals/<model>/}, for the developer to compare with the run by hand.
 */
@DisplayName("init and plan on Spring PetClinic, measured against the run by hand")
class PetClinicEval {

    static final String MODEL = System.getProperty("eval.model", "anthropic:claude-sonnet-5-5");
    /** One judge for every model under test, so their results compare; never a model grading its own work. */
    static final String JUDGE = System.getProperty("eval.judge", "anthropic:claude-sonnet-5-5");
    /**
     * One developer for every model under test, for the same reason: with the model under test playing the
     * developer too, a run would measure how well it plays the developer as well.
     */
    static final String DEVELOPER = System.getProperty("eval.developer", "anthropic:claude-sonnet-5-5");
    static final ReplayMode MODE = ReplayMode.valueOf(System.getProperty("eval.mode", "replay").toUpperCase(Locale.ROOT));
    /** The placeholders' tag, the same every run, so recordings replay. */
    static final String TAG = "e7a1";
    static final int MAX_TURNS = 12;

    @TempDir Path work;
    @TempDir Path replayHome;
    private Path cassettes;
    private Path out;
    private String configBefore;

    @BeforeEach
    void setUp() {
        cassettes = Path.of(System.getProperty("eval.cassettes", SavedKeys.dir().resolve("loop").resolve("evals").toString()))
                .resolve(slug(MODEL));
        out = Path.of(System.getProperty("eval.out", "build/evals")).resolve(slug(MODEL));
        // A replay spends nothing: its recorded tokens stay out of the machine's usage log.
        configBefore = System.getProperty("cafeai.config.dir");
        if (MODE == ReplayMode.REPLAY) System.setProperty("cafeai.config.dir", replayHome.toString());
    }

    @AfterEach
    void tearDown() {
        if (configBefore == null) System.clearProperty("cafeai.config.dir");
        else System.setProperty("cafeai.config.dir", configBefore);
    }

    @Test @DisplayName("5.1: init from PetClinic's base commit, with the developer's recorded answers")
    void init() throws IOException {
        Path project = PetClinic.checkout(Files.createDirectories(work.resolve("spring-petclinic")));
        var developer = new BriefedDeveloper(LangchainBridge.chatModel(developer()), MAX_TURNS);
        int status = new InitCommand(project, developer, settings -> replay("init"), () -> new Screen(TAG)).run();

        Path specFile = project.resolve("DESIGN_SPEC.md");
        String spec = Files.isRegularFile(specFile) ? Files.readString(specFile, StandardCharsets.UTF_8) : "";
        EvalReport report = PetClinicChecks.ofSpec(spec, judge());
        save("init", Map.of("DESIGN_SPEC.md", spec), developer, report);

        assertThat(status).as("init finished (see %s)", out.resolve("init")).isZero();
        report.assertAllPassed();
    }

    @Test @DisplayName("5.1: plan from the run by hand's reviewed spec, with the developer's recorded answers")
    void plan() throws IOException {
        assumeTrue(PetClinic.localClone().isPresent(), "plan starts from the run by hand's spec: -Peval.petclinic=<dir>");
        Path project = PetClinic.checkout(Files.createDirectories(work.resolve("spring-petclinic")));
        Files.writeString(project.resolve("DESIGN_SPEC.md"), PetClinic.planSpec(), StandardCharsets.UTF_8);
        PetClinic.git(project, "add", "DESIGN_SPEC.md");
        PetClinic.git(project, "commit", "-q", "-m", "Add the design spec");

        Map<String, String> before = PlanCheck.snapshot(project);
        var developer = new BriefedDeveloper(LangchainBridge.chatModel(developer()), MAX_TURNS);
        int status = new PlanCommand(project, developer, settings -> replay("plan"), () -> new Screen(TAG)).run();

        Map<String, String> after = PlanCheck.snapshot(project);
        EvalReport report = PetClinicChecks.ofPlan(before, after, judge());
        save("plan", after, developer, report);

        assertThat(status).as("plan finished (see %s)", out.resolve("plan")).isZero();
        report.assertAllPassed();
    }

    /**
     * The model for {@code role}, through its recordings. No temperature is set: newer models refuse
     * one, and a recording fixes every answer anyway.
     */
    private AiProvider replay(String role) {
        return Replay.of(Models.provider(MODEL), cassettes.resolve(role)).mode(MODE).normalize(PetClinicEval::normalize);
    }

    /** The developer, through recordings kept with the model under test's: what it answered is that model's questions. */
    private AiProvider developer() {
        return Replay.of(Models.provider(DEVELOPER), cassettes.resolve("developer-" + slug(DEVELOPER))).mode(MODE)
                .normalize(PetClinicEval::normalize);
    }

    /** The judge, through recordings kept with the model under test's: what it judged is that model's work. */
    private AiProvider judge() {
        return Replay.of(Models.provider(JUDGE), cassettes.resolve("judge-" + slug(JUDGE))).mode(MODE)
                .normalize(PetClinicEval::normalize);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What changes from run to run without changing the conversation: the order of the tools (Java's
     * reflection gives a class's methods in no fixed order, so the same agent lists its tools differently
     * in another run), commit ids, and the loop's version in a stamp.
     */
    static String normalize(String request) {
        String sorted = request;
        try {
            JsonNode root = JSON.readTree(request);
            if (root.path("parameters").path("toolSpecifications") instanceof ArrayNode tools) {
                List<JsonNode> byText = new ArrayList<>();
                tools.forEach(byText::add);
                byText.sort(Comparator.comparing(JsonNode::asText));
                tools.removeAll();
                tools.addAll(byText);
                sorted = JSON.writeValueAsString(root);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("A request that isn't JSON: " + e.getMessage(), e);
        }
        return sorted.replaceAll("\\b[0-9a-f]{7,40}\\b", "<commit>")
                .replaceAll("<!-- cafeai-loop [^>]*-->", "<!-- cafeai-loop -->");
    }

    private void save(String command, Map<String, String> artifacts, BriefedDeveloper developer, EvalReport report)
            throws IOException {
        Path dir = Files.createDirectories(out.resolve(command));
        for (var a : artifacts.entrySet()) Files.writeString(dir.resolve(a.getKey()), a.getValue(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("conversation.md"), developer.transcript(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("report.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.println("cafeai " + command + " on PetClinic, " + MODEL + " (" + MODE + "):\n" + report);
    }

    private static String slug(String model) {
        return model.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
