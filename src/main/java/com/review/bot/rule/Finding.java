package com.review.bot.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One result produced by a rule (AC2, AC4).
 *
 * @param ruleId      stable rule id, e.g. "no-system-out"
 * @param category    QUALITY, SECURITY or ARCHITECTURE
 * @param severity    WARNING, ERROR or BLOCK
 * @param path        file path in the repo
 * @param line        1-based line in the new version of the file
 * @param description what is wrong
 * @param remediation what to change to fix it (actionable)
 * @param fingerprint stable id: rule + path + normalized line text. It does not include
 *                    the line number, so it survives code moving up or down. Used later
 *                    to tell resolved findings from open ones (AC6).
 */
public record Finding(
        String ruleId,
        Category category,
        Severity severity,
        String path,
        int line,
        String description,
        String remediation,
        String fingerprint) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(path, "path");
        requireText(description, "description");
        requireText(remediation, "remediation");
        requireText(fingerprint, "fingerprint");
        if (line < 1) throw new IllegalArgumentException("line must be >= 1, was " + line);
    }

    /** Builds a finding and computes its fingerprint from the offending line's text. */
    public static Finding of(String ruleId, Category category, Severity severity, FileContext file,
                             int line, String description, String remediation) {
        return new Finding(ruleId, category, severity, file.path(), line, description, remediation,
                fingerprint(ruleId, file.path(), file.lineText(line)));
    }

    /** "src/main/java/Foo.java:42" */
    public String location() {
        return path + ":" + line;
    }

    /** Inline PR comment body. */
    public String toMarkdown() {
        return "**[%s · %s] %s**\n\n%s\n\n**Fix:** %s"
                .formatted(severity, category.label(), ruleId, description, remediation);
    }

    static String fingerprint(String ruleId, String path, String lineText) {
        String normalized = lineText.strip().replaceAll("\\s+", " ");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((ruleId + "|" + path + "|" + normalized).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);   // 16 hex chars is plenty per PR
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
