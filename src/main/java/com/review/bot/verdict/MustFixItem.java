package com.review.bot.verdict;

import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Severity;

/**
 * One entry of the must-fix list (AC4): what must change, where, and which finding requires it.
 *
 * @param number      1-based position in the list
 * @param action      what to change (the finding's remediation)
 * @param problem     why (the finding's description)
 * @param fingerprint links the item back to the exact finding that drove the verdict
 */
public record MustFixItem(
        int number,
        String ruleId,
        Severity severity,
        Category category,
        String location,
        String action,
        String problem,
        String fingerprint) {

    static MustFixItem from(int number, Finding f) {
        return new MustFixItem(number, f.ruleId(), f.severity(), f.category(), f.location(),
                f.remediation(), f.description(), f.fingerprint());
    }
}
