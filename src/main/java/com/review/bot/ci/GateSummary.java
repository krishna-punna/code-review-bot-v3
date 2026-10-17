package com.review.bot.ci;

import com.review.bot.review.ReviewResult;
import com.review.bot.rule.Severity;
import com.review.bot.verdict.Verdict;

/**
 * The CI gate line at the top of every review the bot posts (AC7): pass or fail, the verdict,
 * the finding counts for every severity (zeros included), and where the details are.
 *
 *   > **CI gate `rule-review`: ❌ FAIL** · ⛔ BLOCK · BLOCK 0 · ERROR 3 · WARNING 1 · [details per line](…/files)
 */
public final class GateSummary {

    private GateSummary() {}

    /**
     * @param context     the commit status context, e.g. "rule-review"
     * @param detailsUrl  where the per-line details are (the PR's "Files changed" page), or null
     */
    public static String headline(ReviewResult result, String context, String detailsUrl) {
        Verdict v = result.verdict();
        StringBuilder sb = new StringBuilder("> **CI gate `").append(context).append("`: ")
                .append(result.gatePassed() ? "✅ PASS" : "❌ FAIL").append("** · ")
                .append(v.decision().icon()).append(' ').append(v.decision())
                .append(" · ").append(counts(v));
        if (detailsUrl != null) sb.append(" · [details per line](").append(detailsUrl).append(')');
        return sb.append("\n\n").toString();
    }

    /** "BLOCK 0 · ERROR 3 · WARNING 1": every severity, most severe first. */
    public static String counts(Verdict v) {
        return "BLOCK " + v.count(Severity.BLOCK) + " · ERROR " + v.count(Severity.ERROR)
                + " · WARNING " + v.count(Severity.WARNING);
    }
}
