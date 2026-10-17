package com.review.bot.rejudge;

import com.review.bot.review.ReviewResult;

/**
 * @param result   the review of the change as it is now, with the updated verdict
 * @param rejudge  what changed since the previous review, or null if there was none
 * @param state    what the next re-review compares against
 */
public record RejudgeResult(ReviewResult result, Rejudge rejudge, ReviewState state) {}
