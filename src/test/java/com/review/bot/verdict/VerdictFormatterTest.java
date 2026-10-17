package com.review.bot.verdict;

import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerdictFormatterTest {

    private final VerdictEngine engine = new VerdictEngine(VerdictProperties.defaults());

    private static Finding f(Severity s, Category c, int line) {
        return new Finding("rule-" + line, c, s, "src/A.java", line, "problem " + line, "do fix " + line, "fp" + line);
    }

    private final List<Finding> findings = List.of(
            f(Severity.BLOCK, Category.SECURITY, 1),
            f(Severity.ERROR, Category.QUALITY, 2),
            f(Severity.WARNING, Category.QUALITY, 3),
            f(Severity.WARNING, Category.ARCHITECTURE, 4));

    @Test
    void markdownShowsVerdictMustFixAndCounts() {
        String md = VerdictFormatter.markdown(engine.decide(findings), 50);

        assertThat(md)
                .contains("Verdict: **BLOCK**")
                .contains("### Must fix before merge (2)")
                .contains("1. **[BLOCK · Security]** `src/A.java:1` (rule-1)")
                .contains("   - **Action:** do fix 1")
                .contains("   - **Why:** problem 1")
                .contains("2. **[ERROR · Quality]** `src/A.java:2`")
                .contains("Other findings (2, not required for this verdict)")
                .contains("| Severity | Quality | Security | Architecture/Design | Total |")
                .contains("| **BLOCK** | 0 | 1 | 0 | 1 |")
                .contains("| **WARNING** | 1 | 0 | 1 | 2 |")
                .contains("| **Total** | 2 | 1 | 1 | 4 |")
                .contains("Policy: BLOCK on");

        // Warnings are not in the must-fix section for a BLOCK verdict.
        String mustFixSection = md.substring(md.indexOf("### Must fix"), md.indexOf("<details>"));
        assertThat(mustFixSection).doesNotContain("do fix 3", "do fix 4");
    }

    @Test
    void approveHasNoMustFixSection() {
        String md = VerdictFormatter.markdown(engine.decide(List.of()), 50);
        assertThat(md).contains("Verdict: **APPROVE**").doesNotContain("Must fix");
    }

    @Test
    void statusLineShowsVerdictMustFixAndCounts() {
        assertThat(VerdictFormatter.statusLine(engine.decide(findings)))
                .isEqualTo("BLOCK · 2 must-fix · 1 BLOCK, 1 ERROR, 2 WARNING");
        assertThat(VerdictFormatter.statusLine(engine.decide(List.of())))
                .isEqualTo("APPROVE · no findings");
    }

    @Test
    void statusLineNeverExceedsGitHubLimit() {
        List<Finding> many = new ArrayList<>();
        for (int i = 1; i <= 500; i++) many.add(f(Severity.values()[i % 3], Category.QUALITY, i));
        assertThat(VerdictFormatter.statusLine(engine.decide(many)).length())
                .isLessThanOrEqualTo(VerdictFormatter.STATUS_MAX);
    }
}
