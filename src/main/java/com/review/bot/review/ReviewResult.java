package com.review.bot.review;

import com.review.bot.rule.Finding;
import com.review.bot.verdict.Verdict;

import java.util.List;

/**
 * Outcome of one review.
 *
 * @param filesReviewed number of changed files the rules ran on
 * @param findings      every finding on a changed line, most severe first
 * @param verdict       the merge verdict, with its reason, must-fix list and counts
 * @param gatePassed    false if the verdict fails the CI gate (review.verdict.fail-gate-on)
 */
public record ReviewResult(String title, int filesReviewed, List<Finding> findings, Verdict verdict,
                           boolean gatePassed) {

    public ReviewResult {
        findings = List.copyOf(findings);
    }
}
