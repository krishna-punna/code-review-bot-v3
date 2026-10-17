package com.review.bot.verdict;

import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.RuleEngine;
import com.review.bot.rule.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Turns a set of findings into a merge verdict (AC3) and a must-fix list (AC4).
 *
 * Pure and deterministic: no I/O, no clock, no randomness. The same findings, in any order,
 * always give the same verdict. Rules (default policy):
 *
 *   1. any finding whose severity is in block-on            (BLOCK, ERROR) -> BLOCK
 *   2. else any finding whose severity is in request-changes-on (WARNING)  -> REQUEST_CHANGES
 *   3. else                                                                -> APPROVE
 *
 * The findings that triggered the winning rule are the "driving" findings; the must-fix list
 * contains exactly those, most severe first. Everything else is reported as "other findings".
 */
@Component
public class VerdictEngine {

    private final VerdictProperties policy;

    public VerdictEngine(VerdictProperties policy) {
        this.policy = policy;
    }

    public VerdictProperties policy() {
        return policy;
    }

    public Verdict decide(List<Finding> findings) {
        List<Finding> sorted = findings.stream().sorted(RuleEngine.ORDER).toList();

        Decision decision;
        Set<Severity> drivingSeverities;
        if (anyAt(sorted, policy.blockOn())) {
            decision = Decision.BLOCK;
            drivingSeverities = policy.blockOn();
        } else if (anyAt(sorted, policy.requestChangesOn())) {
            decision = Decision.REQUEST_CHANGES;
            drivingSeverities = policy.requestChangesOn();
        } else {
            decision = Decision.APPROVE;
            drivingSeverities = Set.of();
        }

        List<Finding> driving = new ArrayList<>();
        List<Finding> other = new ArrayList<>();
        for (Finding f : sorted) {
            (drivingSeverities.contains(f.severity()) ? driving : other).add(f);
        }

        List<MustFixItem> mustFix = IntStream.range(0, driving.size())
                .mapToObj(i -> MustFixItem.from(i + 1, driving.get(i)))
                .toList();

        return new Verdict(decision, reason(decision, driving, drivingSeverities, sorted.size()),
                policy.describe(), driving, mustFix, other, bySeverity(sorted), byCategory(sorted));
    }

    /** True if this verdict should turn the CI gate red. */
    public boolean failsGate(Verdict verdict) {
        return policy.failGateOn().contains(verdict.decision());
    }

    private static boolean anyAt(List<Finding> findings, Set<Severity> severities) {
        return findings.stream().anyMatch(f -> severities.contains(f.severity()));
    }

    private static String reason(Decision decision, List<Finding> driving, Set<Severity> severities, int total) {
        if (decision == Decision.APPROVE) {
            return total == 0
                    ? "APPROVE: no findings."
                    : "APPROVE: " + total + " advisory finding(s), none at a severity that requires changes.";
        }
        String refs = driving.stream().limit(5)
                .map(f -> f.ruleId() + "@" + f.location())
                .collect(Collectors.joining(", "));
        String more = driving.size() > 5 ? " and " + (driving.size() - 5) + " more" : "";
        return "%s: %d finding(s) at severity %s: %s%s."
                .formatted(decision, driving.size(), VerdictProperties.format(severities), refs, more);
    }

    private static Map<Severity, Long> bySeverity(List<Finding> findings) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) counts.put(s, 0L);
        findings.forEach(f -> counts.merge(f.severity(), 1L, Long::sum));
        return counts;
    }

    private static Map<Category, Long> byCategory(List<Finding> findings) {
        Map<Category, Long> counts = new EnumMap<>(Category.class);
        for (Category c : Category.values()) counts.put(c, 0L);
        findings.forEach(f -> counts.merge(f.category(), 1L, Long::sum));
        return counts;
    }
}
