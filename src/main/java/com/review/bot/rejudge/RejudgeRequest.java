package com.review.bot.rejudge;

import com.review.bot.review.ReviewRequest;

/**
 * Body of POST /api/reviews/rejudge.
 *
 * @param change    the code as it is now, in the same shape as the body of POST /api/reviews
 * @param previous  the "state" from the previous review's response (or the CLI's state.json);
 *                  null for a first review
 */
public record RejudgeRequest(ReviewRequest change, ReviewState previous) {}
