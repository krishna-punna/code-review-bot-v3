package com.review.bot.rule;

/**
 * Severity tag on every finding (AC2). Declared from least to most severe,
 * so {@code compareTo} / {@code isAtLeast} can be used for ordering and thresholds.
 *
 *  WARNING - should be fixed; does not by itself stop a merge.
 *  ERROR   - must be fixed before merge.
 *  BLOCK   - critical (typically security); must be fixed before merge.
 *
 * Turning findings into an APPROVE / REQUEST_CHANGES / BLOCK verdict is the
 * job of {@code VerdictEngine} and its configurable policy, not of this enum.
 */
public enum Severity {
    WARNING, ERROR, BLOCK;

    public boolean isAtLeast(Severity other) {
        return compareTo(other) >= 0;
    }
}
