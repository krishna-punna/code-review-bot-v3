package com.review.bot.verdict;

/** The merge verdict (AC3). Exactly one is produced per review. */
public enum Decision {
    APPROVE("✅"),
    REQUEST_CHANGES("⚠️"),
    BLOCK("⛔");

    private final String icon;

    Decision(String icon) {
        this.icon = icon;
    }

    public String icon() {
        return icon;
    }
}
