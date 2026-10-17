package com.review.bot.verdict;

import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Renders a {@link Verdict} as PR review markdown and as a short CI status line. */
public final class VerdictFormatter {

    /** GitHub rejects commit status descriptions longer than 140 characters. */
    public static final int STATUS_MAX = 140;

    private VerdictFormatter() {}

    /** Summary for output outside GitHub (CLI, REST API), where there are no inline comments. */
    public static String markdown(Verdict v) {
        return markdown(v, Integer.MAX_VALUE);
    }

    /** Full review body: verdict, reason, must-fix list, counts table. */
    public static String markdown(Verdict v, int inlineLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(v.decision().icon()).append(" Verdict: **").append(v.decision()).append("**\n\n");
        sb.append(v.reason()).append("\n\n");

        if (!v.mustFix().isEmpty()) {
            sb.append("### Must fix before merge (").append(v.mustFix().size()).append(")\n\n");
            for (MustFixItem m : v.mustFix()) {
                sb.append(m.number()).append(". **[").append(m.severity()).append(" · ")
                        .append(m.category().label()).append("]** `").append(m.location()).append("` (")
                        .append(m.ruleId()).append(")\n");
                sb.append("   - **Action:** ").append(m.action()).append('\n');
                sb.append("   - **Why:** ").append(m.problem()).append('\n');
            }
            sb.append('\n');
        }

        if (!v.otherFindings().isEmpty()) {
            sb.append("<details><summary>Other findings (").append(v.otherFindings().size())
                    .append(", not required for this verdict)</summary>\n\n");
            for (Finding f : v.otherFindings()) {
                sb.append("- **[").append(f.severity()).append(" · ").append(f.category().label())
                        .append("]** `").append(f.location()).append("` ").append(f.description()).append('\n');
            }
            sb.append("\n</details>\n\n");
        }

        sb.append(countsTable(v));

        if (v.totalFindings() > inlineLimit) {
            sb.append("\nShowing the first ").append(inlineLimit).append(" inline comments.\n");
        }
        sb.append("\n<sub>Policy: ").append(v.policy()).append("</sub>\n");
        return sb.toString();
    }

    /** CI status line, e.g. "BLOCK · 2 must-fix · 1 BLOCK, 1 ERROR, 3 WARNING" (max 140 chars). */
    public static String statusLine(Verdict v) {
        String line;
        if (v.totalFindings() == 0) {
            line = v.decision() + " · no findings";
        } else {
            List<String> parts = new ArrayList<>();
            for (Severity s : descending()) {
                if (v.count(s) > 0) parts.add(v.count(s) + " " + s);
            }
            line = v.decision()
                    + (v.mustFix().isEmpty() ? "" : " · " + v.mustFix().size() + " must-fix")
                    + " · " + String.join(", ", parts);
        }
        return line.length() <= STATUS_MAX ? line : line.substring(0, STATUS_MAX - 1) + "…";
    }

    /** Severity x category count table, most severe first. */
    static String countsTable(Verdict v) {
        Category[] cats = Category.values();
        StringBuilder sb = new StringBuilder("| Severity |");
        for (Category c : cats) sb.append(' ').append(c.label()).append(" |");
        sb.append(" Total |\n|---|").append("---|".repeat(cats.length + 1)).append('\n');

        for (Severity s : descending()) {
            if (v.count(s) == 0) continue;
            sb.append("| **").append(s).append("** |");
            for (Category c : cats) sb.append(' ').append(count(v, s, c)).append(" |");
            sb.append(' ').append(v.count(s)).append(" |\n");
        }
        sb.append("| **Total** |");
        for (Category c : cats) sb.append(' ').append(v.count(c)).append(" |");
        sb.append(' ').append(v.totalFindings()).append(" |\n");
        return sb.toString();
    }

    private static long count(Verdict v, Severity s, Category c) {
        return java.util.stream.Stream.concat(v.drivingFindings().stream(), v.otherFindings().stream())
                .filter(f -> f.severity() == s && f.category() == c)
                .count();
    }

    private static List<Severity> descending() {
        List<Severity> list = new ArrayList<>(List.of(Severity.values()));
        Collections.reverse(list);
        return list;
    }
}
