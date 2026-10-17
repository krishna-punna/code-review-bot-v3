package com.review.bot.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.config.ReviewProperties;
import com.review.bot.metrics.Benchmark.RoundRun;
import com.review.bot.metrics.Benchmark.SampleRun;
import com.review.bot.metrics.ManualBaseline.Row;
import com.review.bot.rejudge.RejudgeService;
import com.review.bot.review.ReviewService;
import com.review.bot.rule.RuleEngine;
import com.review.bot.rule.impl.LayerDependencyRule;
import com.review.bot.rule.impl.MaxMethodLengthRule;
import com.review.bot.rule.impl.NoFieldInjectionRule;
import com.review.bot.rule.impl.NoSystemOutRule;
import com.review.bot.verdict.Decision;
import com.review.bot.verdict.VerdictEngine;
import com.review.bot.verdict.VerdictProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** AC8: the bot's side of the comparison, measured on the real sample PRs, and the report built from it. */
class BenchmarkTest {

    private static Benchmark.Result result;

    @BeforeAll
    static void runOnTheSamples() throws IOException {
        ReviewProperties props = new ReviewProperties("t", null, null, List.of(), Map.of());
        RuleEngine engine = new RuleEngine(List.of(new NoSystemOutRule(), new NoFieldInjectionRule(),
                new MaxMethodLengthRule(props), new LayerDependencyRule()), props);
        VerdictEngine verdicts = new VerdictEngine(VerdictProperties.defaults());
        ReviewService reviews = new ReviewService(engine, verdicts);
        result = new Benchmark(reviews, new RejudgeService(reviews, engine, verdicts, props), new ObjectMapper())
                .run(Path.of("samples"), Path.of("samples-rejudge"), 3);
    }

    @Test
    void everySampleIsTimed_andGivesItsKnownVerdict_theSameEveryRun() {
        assertThat(result.samples()).extracting(SampleRun::sample, SampleRun::decision).containsExactly(
                org.assertj.core.groups.Tuple.tuple("01-clean-change-approve", Decision.APPROVE),
                org.assertj.core.groups.Tuple.tuple("02-warnings-request-changes", Decision.REQUEST_CHANGES),
                org.assertj.core.groups.Tuple.tuple("03-errors-block", Decision.BLOCK));
        assertThat(result.samples()).allSatisfy(s -> {
            assertThat(s.deterministic()).isTrue();
            assertThat(s.millis().count()).isEqualTo(3);
            assertThat(s.millis().median()).isPositive();
        });
    }

    @Test
    void everyFindingIsLocatedTaggedAndHasAFix() {
        assertThat(result.samples()).allSatisfy(s -> {
            Benchmark.Clarity c = s.clarity();
            assertThat(c.withLocation()).isEqualTo(c.findings());
            assertThat(c.withSeverityAndCategory()).isEqualTo(c.findings());
            assertThat(c.withFix()).isEqualTo(c.findings());
            assertThat(c.explicitVerdict()).isTrue();
            assertThat(c.mustFixOnlyBlocking()).isTrue();
        });
    }

    @Test
    void rejudgeSample_isApprovedAtTheThirdSubmission_reevaluatingOnlyWhatChanged() {
        assertThat(result.rounds()).extracting(RoundRun::decision)
                .containsExactly(Decision.BLOCK, Decision.REQUEST_CHANGES, Decision.APPROVE);
        assertThat(result.roundsToApprove()).isEqualTo(3);
        RoundRun second = result.rounds().get(1);
        assertThat(second.resolved()).isEqualTo(3);
        assertThat(second.unresolved()).isEqualTo(2);
        assertThat(second.newFindings()).isEqualTo(1);
        assertThat(second.reevaluated()).isEqualTo(1);
        assertThat(second.files()).isEqualTo(2);
    }

    @Test
    void reportWithoutBaseline_saysSoInsteadOfGuessing() {
        String report = Ac8Report.markdown(result, List.of(), null, "2026-10-17", "test");

        assertThat(report).contains("No manual baseline yet", "not recorded yet", "Approved at submission 3",
                "| Issues located by file and line | 6 of 6 issues (100%) | not recorded yet |");
        assertThat(report).doesNotContain("## 4. Review history");
    }

    @Test
    void reportWithBaseline_putsManualAndBotSideBySide() {
        List<Row> baseline = List.of(
                new Row("03-errors-block", "asha", 14, "BLOCK", 3, 2, 1, 1),
                new Row("samples/03-errors-block", "ben", 10, "REQUEST_CHANGES", 2, 2, 2, 1),
                new Row("samples-rejudge", "asha", 35, "APPROVE", 6, 4, 3, 4));

        String report = Ac8Report.markdown(result, baseline, null, "2026-10-17", "test");

        assertThat(report).contains("| 12.0 min (2 reviewers) | BLOCK, REQUEST_CHANGES |",
                "**Result:** a manual review of these samples took a median of **12.0 min**",
                "a median of **4 review rounds** and **35.0 min**",
                "| States an explicit merge decision | 3 of 3 reviews (100%) | 3 of 3 reviews (100%) |",
                "| Issues located by file and line | 6 of 6 issues (100%) | 8 of 11 issues (73%) |",
                "| Issues with a concrete fix | 6 of 6 issues (100%) | 6 of 11 issues (55%) |");
    }

    @Test
    void reportWithHistory_addsTheHistorySection() {
        PrHistory.Summary history = PrHistory.analyze("acme/app", List.of(), "rule-review");

        assertThat(Ac8Report.markdown(result, List.of(), history, "2026-10-17", "test"))
                .contains("## 4. Review history: `acme/app`", "| Time to first human review | – | – | 0 |");
    }
}
