package com.review.bot.rejudge;

import com.review.bot.review.ReviewReport;

/**
 * A re-judged review as JSON: the response of POST /api/reviews/rejudge.
 *
 * @param report           the change as it is now, in the same shape as POST /api/reviews returns
 * @param rejudge          resolved / still open / new since the previous review; null on a first review
 * @param state            send this back as "previous" with the next resubmission
 * @param summaryMarkdown  the summary, starting with what changed since the previous review
 */
public record RejudgeReport(ReviewReport report, Rejudge rejudge, ReviewState state, String summaryMarkdown) {

    public static RejudgeReport of(RejudgeResult r) {
        return new RejudgeReport(ReviewReport.of(r.result()), r.rejudge(), r.state(), RejudgeFormatter.markdown(r));
    }
}
