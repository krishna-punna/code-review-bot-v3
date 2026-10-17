package com.review.bot.rejudge;

import com.review.bot.rule.Finding;
import com.review.bot.verdict.Decision;

import java.util.List;

/**
 * How a change moved since its previous review (AC6). Findings are matched by fingerprint
 * (rule + file + text of the offending line), so they survive code moving up or down.
 *
 * @param previousDecision  the verdict of the previous review
 * @param previousCommit    the commit the previous review saw (GitHub), or null
 * @param reevaluated       files the rules ran on again, because they changed
 * @param carriedOver       files unchanged since the previous review; their findings were reused
 * @param fullReason        why every file was re-evaluated even if unchanged, or null
 * @param resolved          previous findings that are gone
 * @param unresolved        previous findings that are still there (at their current location)
 * @param newFindings       findings the previous review didn't have
 */
public record Rejudge(Decision previousDecision, String previousCommit, List<String> reevaluated,
                      List<String> carriedOver, String fullReason, List<Finding> resolved,
                      List<Finding> unresolved, List<Finding> newFindings) {

    public Rejudge {
        reevaluated = List.copyOf(reevaluated);
        carriedOver = List.copyOf(carriedOver);
        resolved = List.copyOf(resolved);
        unresolved = List.copyOf(unresolved);
        newFindings = List.copyOf(newFindings);
    }
}
