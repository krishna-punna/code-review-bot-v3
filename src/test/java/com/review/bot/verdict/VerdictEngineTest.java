package com.review.bot.verdict;

import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 2: AC3 (deterministic, traceable verdict) and AC4 (must-fix list). */
class VerdictEngineTest {

    private final VerdictEngine engine = new VerdictEngine(VerdictProperties.defaults());

    private static int counter = 0;

    private static Finding f(Severity s) {
        return f(s, Category.QUALITY);
    }

    private static Finding f(Severity s, Category c) {
        int n = ++counter;
        return new Finding("rule-" + s.name().toLowerCase(), c, s, "src/A" + n + ".java", n,
                "problem " + n, "do fix " + n, "fp" + n);
    }

    /** "B,E,W" -> one finding per letter. Empty string -> no findings. */
    private static List<Finding> parse(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        return Arrays.stream(spec.split(","))
                .map(String::trim)
                .map(x -> switch (x) {
                    case "B" -> f(Severity.BLOCK);
                    case "E" -> f(Severity.ERROR);
                    case "W" -> f(Severity.WARNING);
                    default -> throw new IllegalArgumentException(x);
                })
                .toList();
    }

    // ---- AC3: decision table with the default policy -----------------------------------------

    @ParameterizedTest(name = "[{0}] -> {1}, {2} must-fix")
    @CsvSource(delimiter = '|', value = {
            "         | APPROVE         | 0",
            "W        | REQUEST_CHANGES | 1",
            "W,W,W    | REQUEST_CHANGES | 3",
            "E        | BLOCK           | 1",
            "B        | BLOCK           | 1",
            "W,E      | BLOCK           | 1",
            "W,W,E    | BLOCK           | 1",
            "B,E,W    | BLOCK           | 2",
            "B,B,E,W  | BLOCK           | 3",
    })
    void decisionTable(String spec, Decision expected, int mustFix) {
        Verdict v = engine.decide(parse(spec));
        assertThat(v.decision()).isEqualTo(expected);
        assertThat(v.mustFix()).hasSize(mustFix);
        assertThat(v.totalFindings()).isEqualTo(parse(spec == null ? "" : spec).size());
    }

    @Test
    void anyErrorForcesBlock() {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < 20; i++) findings.add(f(Severity.WARNING));
        findings.add(f(Severity.ERROR));
        assertThat(engine.decide(findings).decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void sameFindingsInAnyOrderGiveTheSameVerdict() {
        List<Finding> findings = parse("W,B,E,W,E,B,W");
        Verdict reference = engine.decide(findings);

        Random random = new Random(42);   // fixed seed: the test itself is deterministic
        for (int i = 0; i < 25; i++) {
            List<Finding> shuffled = new ArrayList<>(findings);
            Collections.shuffle(shuffled, random);
            assertThat(engine.decide(shuffled)).isEqualTo(reference);
        }
    }

    // ---- AC3: traceability -------------------------------------------------------------------

    @Test
    void reasonNamesTheFindingsThatDroveTheVerdict() {
        Finding error = f(Severity.ERROR);
        Finding warning = f(Severity.WARNING);
        Verdict v = engine.decide(List.of(warning, error));

        assertThat(v.drivingFindings()).containsExactly(error);
        assertThat(v.otherFindings()).containsExactly(warning);
        assertThat(v.reason())
                .startsWith("BLOCK: 1 finding(s)")
                .contains(error.ruleId() + "@" + error.location())
                .doesNotContain(warning.location());
        assertThat(v.policy()).contains("BLOCK on", "REQUEST_CHANGES on");
    }

    @Test
    void reasonIsCappedForLargeChanges() {
        List<Finding> many = new ArrayList<>();
        for (int i = 0; i < 8; i++) many.add(f(Severity.ERROR));
        assertThat(engine.decide(many).reason()).endsWith("and 3 more.");
    }

    @Test
    void approveExplainsItself() {
        assertThat(engine.decide(List.of()).reason()).isEqualTo("APPROVE: no findings.");
        assertThat(engine.decide(List.of()).drivingFindings()).isEmpty();
    }

    // ---- AC4: must-fix list ------------------------------------------------------------------

    @Test
    void mustFixContainsOnlyBlockingFindings_mostSevereFirst_andIsActionable() {
        Finding warning = f(Severity.WARNING);
        Finding error = f(Severity.ERROR, Category.ARCHITECTURE);
        Finding block = f(Severity.BLOCK, Category.SECURITY);

        Verdict v = engine.decide(List.of(warning, error, block));

        assertThat(v.mustFix()).extracting(MustFixItem::fingerprint)
                .containsExactly(block.fingerprint(), error.fingerprint());
        assertThat(v.mustFix()).extracting(MustFixItem::number).containsExactly(1, 2);

        MustFixItem first = v.mustFix().get(0);
        assertThat(first.action()).isEqualTo(block.remediation());
        assertThat(first.problem()).isEqualTo(block.description());
        assertThat(first.location()).isEqualTo(block.location());
        assertThat(first.category()).isEqualTo(Category.SECURITY);
    }

    @Test
    void requestChangesMustFixListsTheWarnings() {
        Verdict v = engine.decide(parse("W,W"));
        assertThat(v.mustFix()).hasSize(2).allSatisfy(m -> assertThat(m.severity()).isEqualTo(Severity.WARNING));
    }

    @Test
    void approveHasNoMustFix() {
        assertThat(engine.decide(List.of()).mustFix()).isEmpty();
    }

    // ---- Counts ------------------------------------------------------------------------------

    @Test
    void countsCoverEverySeverityAndCategoryIncludingZeros() {
        Verdict v = engine.decide(List.of(f(Severity.ERROR, Category.SECURITY), f(Severity.WARNING)));
        assertThat(v.bySeverity()).containsOnlyKeys(Severity.values());
        assertThat(v.byCategory()).containsOnlyKeys(Category.values());
        assertThat(v.count(Severity.BLOCK)).isZero();
        assertThat(v.count(Severity.ERROR)).isEqualTo(1);
        assertThat(v.count(Category.SECURITY)).isEqualTo(1);
        assertThat(v.count(Category.ARCHITECTURE)).isZero();
    }

    // ---- Configurable policy -----------------------------------------------------------------

    @Test
    void customPolicy_onlyBlockSeverityBlocks_warningsAdvisory() {
        VerdictEngine lenient = new VerdictEngine(new VerdictProperties(
                EnumSet.of(Severity.BLOCK), EnumSet.of(Severity.ERROR), null));

        assertThat(lenient.decide(parse("W")).decision()).isEqualTo(Decision.APPROVE);
        assertThat(lenient.decide(parse("W")).reason()).contains("advisory");
        assertThat(lenient.decide(parse("E,W")).decision()).isEqualTo(Decision.REQUEST_CHANGES);
        assertThat(lenient.decide(parse("E,W")).mustFix()).hasSize(1);
        assertThat(lenient.decide(parse("B,E")).decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void emptyRequestChangesListMakesWarningsAdvisory() {
        VerdictEngine e = new VerdictEngine(new VerdictProperties(null, Set.of(), null));
        assertThat(e.decide(parse("W")).decision()).isEqualTo(Decision.APPROVE);
        assertThat(e.decide(parse("E")).decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void overlappingPolicyIsRejectedAtStartup() {
        assertThatThrownBy(() -> new VerdictProperties(
                EnumSet.of(Severity.ERROR), EnumSet.of(Severity.ERROR, Severity.WARNING), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ERROR");
    }

    @Test
    void approveCannotFailTheGate() {
        assertThatThrownBy(() -> new VerdictProperties(null, null, Set.of(Decision.APPROVE)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- CI gate -----------------------------------------------------------------------------

    @Test
    void gateFailsOnBlockAndRequestChangesByDefault() {
        assertThat(engine.failsGate(engine.decide(parse("E")))).isTrue();
        assertThat(engine.failsGate(engine.decide(parse("W")))).isTrue();
        assertThat(engine.failsGate(engine.decide(List.of()))).isFalse();
    }

    @Test
    void gateCanBeLimitedToBlockOnly() {
        VerdictEngine e = new VerdictEngine(new VerdictProperties(null, null, Set.of(Decision.BLOCK)));
        assertThat(e.failsGate(e.decide(parse("W")))).isFalse();
        assertThat(e.failsGate(e.decide(parse("E")))).isTrue();
    }
}
