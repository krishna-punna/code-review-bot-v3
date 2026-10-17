package com.review.bot.verdict;

import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Severity;

import java.util.List;
import java.util.Map;

/**
 * Result of judging one change (AC3, AC4).
 *
 * @param decision          exactly one of APPROVE, REQUEST_CHANGES, BLOCK
 * @param reason            one sentence explaining which findings forced the decision
 * @param policy            the policy that was applied, for traceability
 * @param drivingFindings   the findings that caused the decision (empty for APPROVE)
 * @param mustFix           actionable list built from drivingFindings (empty for APPROVE)
 * @param otherFindings     findings reported but not required for this decision
 * @param bySeverity        counts for every severity (zeros included)
 * @param byCategory        counts for every category (zeros included)
 */
public record Verdict(
        Decision decision,
        String reason,
        String policy,
        List<Finding> drivingFindings,
        List<MustFixItem> mustFix,
        List<Finding> otherFindings,
        Map<Severity, Long> bySeverity,
        Map<Category, Long> byCategory) {

    public Verdict {
        drivingFindings = List.copyOf(drivingFindings);
        mustFix = List.copyOf(mustFix);
        otherFindings = List.copyOf(otherFindings);
        bySeverity = Map.copyOf(bySeverity);
        byCategory = Map.copyOf(byCategory);
    }

    public int totalFindings() {
        return drivingFindings.size() + otherFindings.size();
    }

    public long count(Severity severity) {
        return bySeverity.getOrDefault(severity, 0L);
    }

    public long count(Category category) {
        return byCategory.getOrDefault(category, 0L);
    }
}
