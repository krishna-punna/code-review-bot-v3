package com.review.bot.rule;

/** Risk category of a finding (AC2). Every rule belongs to exactly one. */
public enum Category {
    QUALITY("Quality"),
    SECURITY("Security"),
    ARCHITECTURE("Architecture/Design");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
