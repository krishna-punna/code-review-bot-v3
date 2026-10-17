package com.review.bot.verdict;

import com.review.bot.rule.Severity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Verdict policy, bound from "review.verdict" in application.yml.
 *
 * review:
 *   verdict:
 *     block-on: [BLOCK, ERROR]                  # any finding at these severities -> BLOCK
 *     request-changes-on: [WARNING]             # else any finding at these       -> REQUEST_CHANGES
 *     fail-gate-on: [BLOCK, REQUEST_CHANGES]    # verdicts that turn the CI status red
 *
 * A severity listed in neither set is advisory: it is reported but never affects the verdict.
 */
@ConfigurationProperties(prefix = "review.verdict")
public record VerdictProperties(
        Set<Severity> blockOn,
        Set<Severity> requestChangesOn,
        Set<Decision> failGateOn) {

    public VerdictProperties {
        blockOn = freeze(blockOn, EnumSet.of(Severity.BLOCK, Severity.ERROR));
        requestChangesOn = freeze(requestChangesOn, EnumSet.of(Severity.WARNING));
        failGateOn = failGateOn == null || failGateOn.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.of(Decision.BLOCK, Decision.REQUEST_CHANGES))
                : Collections.unmodifiableSet(EnumSet.copyOf(failGateOn));

        Set<Severity> overlap = EnumSet.noneOf(Severity.class);   // copyOf() throws on an empty non-EnumSet
        overlap.addAll(blockOn);
        overlap.retainAll(requestChangesOn);
        if (!overlap.isEmpty()) {
            throw new IllegalStateException("review.verdict: severities " + overlap
                    + " are in both block-on and request-changes-on; each severity may map to one verdict only");
        }
        if (failGateOn.contains(Decision.APPROVE)) {
            throw new IllegalStateException("review.verdict.fail-gate-on must not contain APPROVE");
        }
    }

    /** Defaults: BLOCK/ERROR -> BLOCK, WARNING -> REQUEST_CHANGES, gate fails on both. */
    public static VerdictProperties defaults() {
        return new VerdictProperties(null, null, null);
    }

    /** Human-readable policy, included in every verdict for traceability. */
    public String describe() {
        return "BLOCK on " + format(blockOn) + "; REQUEST_CHANGES on " + format(requestChangesOn)
                + "; otherwise APPROVE";
    }

    /** "[BLOCK, ERROR]": most severe first, for readable output. */
    public static String format(Set<Severity> severities) {
        return severities.stream()
                .sorted(java.util.Comparator.reverseOrder())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private static Set<Severity> freeze(Set<Severity> value, EnumSet<Severity> fallback) {
        // An explicitly empty list in yml is allowed (e.g. request-changes-on: [] makes warnings advisory).
        EnumSet<Severity> set = value == null ? fallback
                : value.isEmpty() ? EnumSet.noneOf(Severity.class) : EnumSet.copyOf(value);
        return Collections.unmodifiableSet(set);
    }
}
