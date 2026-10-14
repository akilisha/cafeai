package io.cafeai.loop;

import io.cafeai.test.EvalReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ROADMAP-03, phase 5's testing criteria: the evals' checks pass on the run by hand, and fail when
 * what they check is broken on purpose. No model: the checks are code. Needs the local PetClinic
 * clone, where the run by hand lives ({@code -Peval.petclinic=<dir>}).
 */
@DisplayName("The evals' checks: the run by hand passes; each broken on purpose, fails")
class PetClinicChecksTest {

    private static String spec;
    private static Map<String, String> plan;

    @BeforeAll
    static void handArtifacts() {
        assumeTrue(PetClinic.localClone().isPresent(), "no local PetClinic clone: -Peval.petclinic=<dir>");
        spec = PetClinic.handSpec();
        plan = PetClinic.handPlan();
    }

    private static EvalReport spec(UnaryOperator<String> broken) {
        return PetClinicChecks.ofSpec(broken.apply(spec), null);
    }

    private static EvalReport plan(String file, UnaryOperator<String> broken) {
        var after = new HashMap<>(plan);
        after.put(file, broken.apply(after.get(file)));
        return PetClinicChecks.ofPlan(Map.of(), after, null);
    }

    /** Every roadmap broken the same way. */
    private static EvalReport roadmaps(UnaryOperator<String> broken) {
        var after = new HashMap<String, String>();
        plan.forEach((k, v) -> after.put(k, k.startsWith("ROADMAP-") ? broken.apply(v) : v));
        return PetClinicChecks.ofPlan(Map.of(), after, null);
    }

    /** The checks that failed, by name. */
    private static String failed(EvalReport report) {
        return report.failures().stream().flatMap(r -> r.checks().stream()).filter(c -> !c.passed())
                .map(EvalReport.CheckResult::check).reduce("", (a, b) -> a + b + "\n");
    }

    @Test @DisplayName("the run by hand passes every check")
    void handPasses() {
        assertThat(failed(PetClinicChecks.ofSpec(spec, null))).isEmpty();
        assertThat(failed(PetClinicChecks.ofPlan(Map.of(), plan, null))).isEmpty();
    }

    @Test @DisplayName("init's checks fail when what they check is taken out")
    void specBroken() {
        assertThat(failed(spec(s -> s.replaceAll("(?m)^## (\\d+\\. )?Risks.*$", "## Hazards"))))
                .contains("every section");
        assertThat(failed(spec(s -> s.replaceAll("(?i)\\bsystem\\b", "infra")))).contains("packages");
        assertThat(failed(spec(s -> s.replaceAll("(?i)(sign|log)s?[ -]?(in|on)\\b", "visit").replaceAll("(?i)sign-in|login|authentication", "x"))))
                .contains("nobody signs in");
        assertThat(failed(spec(s -> s.replaceAll("(?i)\\bstatus\\b", "state")))).contains("no time, vet or status");
        assertThat(failed(spec(s -> s.replaceAll("(?i)gradle", "ant")))).contains("Maven and Gradle");
        assertThat(failed(spec(s -> s.replaceAll("(?i)postgre\\w*", "SQLite")))).contains("three databases");
        assertThat(failed(spec(s -> s.replaceAll("(?i)\\bDCO\\b|sign-?off|signed-off-by", "review")))).contains("DCO");
        assertThat(failed(spec(s -> s.replace("feature/", "topic/")))).contains("feature/");
        assertThat(failed(spec(s -> s.replaceAll("(?i)spring-javaformat", "a formatter")))).contains("spring-javaformat");
        String decisions = PetClinicChecks.section(spec, "Decisions");
        assertThat(decisions).as("the fixture has a Decisions section").isNotBlank();
        assertThat(failed(spec(s -> s.replace(decisions, decisions + "- Migrated visits get the status `COMPLETED`.\n"))))
                .contains("migrated visits");
    }

    @Test @DisplayName("plan's checks fail when what they check is broken")
    void planBroken() {
        assertThat(failed(plan("MILESTONE-01.md", m -> m.replaceFirst("- \\*\\*1\\.1\\*\\* open\\n", ""))))
                .contains("plan check");
        var two = new HashMap<>(plan);
        two.keySet().removeIf(k -> k.endsWith("-03.md"));
        two.put("README.md", plan.get("README.md").replaceAll("(?m)^.*ROADMAP-03.*$\\n", ""));
        assertThat(failed(PetClinicChecks.ofPlan(Map.of(), two, null))).contains("three roadmaps");
        var few = new HashMap<>(plan);
        few.keySet().removeIf(k -> !k.endsWith("-01.md") && !k.equals("README.md"));
        assertThat(failed(PetClinicChecks.ofPlan(Map.of(), few, null))).contains("25 criteria");
        assertThat(failed(plan("ROADMAP-01.md", r -> r.replaceFirst("(- \\*\\*1\\.1\\*\\*)", "$1 A robust and user-friendly"))))
                .contains("vague words");
        assertThat(failed(roadmaps(r -> r.replace("09:00", "9am")))).contains("09:00");
        assertThat(failed(roadmaps(r -> r.replaceAll("(?i)30[ -]?min\\w*", "half an hour")))).contains("30 minutes");
        assertThat(failed(roadmaps(r -> r.replaceAll("1,?024", "a thousand")))).contains("1,024");
        assertThat(failed(roadmaps(r -> r.replaceAll("(?i)postgre\\w*", "SQLite")))).contains("three databases");
        var noReadings = new HashMap<String, String>();
        plan.forEach((k, v) -> noReadings.put(k, k.startsWith("MILESTONE-") ? v.replaceAll("(?i)confirm", "check") : v));
        assertThat(failed(PetClinicChecks.ofPlan(Map.of(), noReadings, null))).contains("readings to confirm");
    }

    @Test @DisplayName("plan's spec is the run by hand's with the developer's migration decision, the assumption gone")
    void planSpec() {
        String corrected = PetClinic.planSpec();
        assertThat(corrected).doesNotContain("they are all in the past").contains("become `SCHEDULED`")
                .contains("(see §6)");
        assertThat(PetClinicChecks.section(corrected, "Decisions")).contains("Migrated visits");
        assertThat(SpecCheck.of(corrected, true).missing()).isEmpty();
    }

    @Test @DisplayName("the visit gap is found however it is said; a visit described without it isn't")
    void visitGap() {
        assertThat(PetClinicChecks.visitLacks("- `Visit` has only a `LocalDate` date and a description.")).isTrue();
        assertThat(PetClinicChecks.visitLacks("A Visit has a date and a description. No time, no vet, no status.")).isTrue();
        assertThat(PetClinicChecks.visitLacks("A Visit has a date, a time, a vet and a status.")).isFalse();
        assertThat(PetClinicChecks.visitLacks("A Visit has a date and a description.")).isFalse();
    }

    @Test @DisplayName("a vague criterion is caught by its words; a precise one isn't")
    void vague() {
        assertThat(PetClinicChecks.vague("The schedule loads quickly.")).isTrue();
        assertThat(PetClinicChecks.vague("An intuitive form.")).isTrue();
        assertThat(PetClinicChecks.vague("The schedule loads within 2 seconds.")).isFalse();
    }
}
