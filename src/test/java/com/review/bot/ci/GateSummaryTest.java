package com.review.bot.ci;

import com.review.bot.review.ReviewResult;
import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Severity;
import com.review.bot.verdict.Verdict;
import com.review.bot.verdict.VerdictEngine;
import com.review.bot.verdict.VerdictProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AC7: the gate line shows pass/fail, the verdict, counts for every severity and a link to details. */
class GateSummaryTest {

    private final VerdictEngine verdicts = new VerdictEngine(VerdictProperties.defaults());

    private ReviewResult result(Finding... findings) {
        Verdict v = verdicts.decide(List.of(findings));
        return new ReviewResult("t", 1, List.of(findings), v, !verdicts.failsGate(v));
    }

    private static Finding finding(Severity severity, int line) {
        return new Finding("rule-" + line, Category.QUALITY, severity, "src/A.java", line, "problem", "fix", "fp" + line);
    }

    @Test
    void failingGate_showsVerdictCountsAndLink() {
        String line = GateSummary.headline(result(finding(Severity.ERROR, 1), finding(Severity.ERROR, 2),
                finding(Severity.WARNING, 3)), "rule-review", "https://github.com/acme/app/pull/7/files");

        assertThat(line).isEqualTo("> **CI gate `rule-review`: ❌ FAIL** · ⛔ BLOCK · BLOCK 0 · ERROR 2 · WARNING 1"
                + " · [details per line](https://github.com/acme/app/pull/7/files)\n\n");
    }

    @Test
    void passingGate_listsZeroCountsToo() {
        assertThat(GateSummary.headline(result(), "rule-review", null))
                .isEqualTo("> **CI gate `rule-review`: ✅ PASS** · ✅ APPROVE · BLOCK 0 · ERROR 0 · WARNING 0\n\n");
    }

    @Test
    void summaryOnApprove_isOnUnlessTurnedOff() {
        assertThat(CiProperties.defaults().summaryOnApprove()).isTrue();
        assertThat(new CiProperties(false).summaryOnApprove()).isFalse();
    }
}
