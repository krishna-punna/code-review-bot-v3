package com.review.bot.review;

import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Severity;
import com.review.bot.verdict.Decision;
import com.review.bot.verdict.MustFixItem;
import com.review.bot.verdict.Verdict;
import com.review.bot.verdict.VerdictFormatter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A review as JSON: the response of POST /api/reviews, and result.json from the CLI.
 * Maps are filled in a fixed order (most severe first), so the same change always gives the same bytes.
 *
 * @param gate             "PASS" or "FAIL", the CI gate outcome for this verdict
 * @param mustFix          what must change before merge (empty for APPROVE)
 * @param findings         every finding, most severe first; driving = it decided the verdict
 * @param summaryMarkdown  the same summary the bot posts on a GitHub PR, with the title on top
 */
public record ReviewReport(
        String title,
        Decision decision,
        String gate,
        String reason,
        String policy,
        String statusLine,
        int filesReviewed,
        Map<Severity, Long> countsBySeverity,
        Map<Category, Long> countsByCategory,
        List<MustFixItem> mustFix,
        List<FindingView> findings,
        String summaryMarkdown) {

    public record FindingView(String ruleId, Severity severity, Category category, String path, int line,
                              String location, String description, String remediation, String fingerprint,
                              boolean driving) {}

    public static ReviewReport of(ReviewResult result) {
        Verdict v = result.verdict();
        Map<Severity, Long> bySeverity = new LinkedHashMap<>();
        for (Severity s : List.of(Severity.BLOCK, Severity.ERROR, Severity.WARNING)) bySeverity.put(s, v.count(s));
        Map<Category, Long> byCategory = new LinkedHashMap<>();
        for (Category c : Category.values()) byCategory.put(c, v.count(c));

        List<FindingView> findings = result.findings().stream()
                .map(f -> view(f, v.drivingFindings().contains(f)))
                .toList();
        String markdown = "# " + result.title() + "\n\n" + result.filesReviewed() + " changed file(s) reviewed.\n\n"
                + VerdictFormatter.markdown(v);

        return new ReviewReport(result.title(), v.decision(), result.gatePassed() ? "PASS" : "FAIL", v.reason(),
                v.policy(), VerdictFormatter.statusLine(v), result.filesReviewed(), bySeverity, byCategory,
                v.mustFix(), findings, markdown);
    }

    private static FindingView view(Finding f, boolean driving) {
        return new FindingView(f.ruleId(), f.severity(), f.category(), f.path(), f.line(), f.location(),
                f.description(), f.remediation(), f.fingerprint(), driving);
    }
}
