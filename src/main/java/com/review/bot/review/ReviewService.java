package com.review.bot.review;

import com.review.bot.rule.FileContext;
import com.review.bot.rule.Finding;
import com.review.bot.rule.RuleEngine;
import com.review.bot.verdict.Verdict;
import com.review.bot.verdict.VerdictEngine;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The governance workflow, the same for every entry point (GitHub webhook, poller, REST API, CLI):
 * run every enabled rule on the changed lines of each file, then turn the findings into a verdict.
 */
@Service
public class ReviewService {

    private final RuleEngine engine;
    private final VerdictEngine verdicts;

    public ReviewService(RuleEngine engine, VerdictEngine verdicts) {
        this.engine = engine;
        this.verdicts = verdicts;
    }

    public ReviewResult review(ChangeSet change) {
        List<Finding> findings = change.files().stream()
                .flatMap(f -> engine.review(new FileContext(f.path(), f.content(), f.changedLines())).stream())
                .sorted(RuleEngine.ORDER)   // stable across file order, so the output is too
                .toList();
        Verdict verdict = verdicts.decide(findings);
        return new ReviewResult(change.title(), change.files().size(), findings, verdict, !verdicts.failsGate(verdict));
    }
}
