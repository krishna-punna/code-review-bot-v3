package com.review.bot.rule;

import java.util.List;

/**
 * A deterministic review rule. Same input, same output, every time.
 * Implementations are Spring beans; the engine discovers them automatically.
 */
public interface Rule {

    /** Stable id used in config (enable/disable, severity override) and in comments. */
    String id();

    /** Risk category every finding of this rule belongs to. */
    Category category();

    /** Default severity; can be overridden in application.yml. */
    Severity defaultSeverity();

    /** Default "how to fix" text. A finding may give a more specific one. */
    String remediation();

    /** Cheap pre-filter so rules skip files they don't care about. */
    default boolean appliesTo(FileContext file) {
        return file.isJava();
    }

    /**
     * Report every finding in the file. The engine drops findings on lines
     * the PR didn't touch, so rules don't need to know about the diff.
     */
    List<Finding> check(FileContext file, Severity severity);

    /** Builds a finding with this rule's id, category and default remediation. */
    default Finding finding(FileContext file, int line, Severity severity, String description) {
        return finding(file, line, severity, description, remediation());
    }

    /** Builds a finding with a finding-specific remediation. */
    default Finding finding(FileContext file, int line, Severity severity, String description, String remediation) {
        return Finding.of(id(), category(), severity, file, line, description, remediation);
    }
}
