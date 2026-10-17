package com.review.bot.rejudge;

import com.review.bot.review.ReviewReport;
import com.review.bot.review.ReviewResult;
import com.review.bot.rule.Finding;
import com.review.bot.verdict.VerdictFormatter;

import java.util.List;

/** Renders what changed since the previous review, on top of the usual verdict summary. */
public final class RejudgeFormatter {

    private RejudgeFormatter() {}

    /** Full summary for the CLI and API. Without a previous review it is the plain AC1 summary. */
    public static String markdown(RejudgeResult r) {
        ReviewResult result = r.result();
        if (r.rejudge() == null) return ReviewReport.of(result).summaryMarkdown();
        return "# " + result.title() + "\n\n" + result.filesReviewed() + " changed file(s) reviewed.\n\n"
                + section(r.rejudge(), result) + "\n"
                + VerdictFormatter.markdown(result.verdict());
    }

    /** "Since the previous review": verdict change, what was re-evaluated, resolved / still open / new. */
    public static String section(Rejudge r, ReviewResult now) {
        StringBuilder sb = new StringBuilder("## 🔁 Since the previous review\n\n");
        sb.append("**Verdict: ").append(r.previousDecision().icon()).append(' ').append(r.previousDecision())
                .append(" → ").append(now.verdict().decision().icon()).append(' ').append(now.verdict().decision())
                .append("**");
        if (r.previousCommit() != null) sb.append(" (previous review: `").append(abbreviate(r.previousCommit())).append("`)");
        sb.append("\n\n");

        sb.append("| ✅ Resolved | ❌ Still open | 🆕 New |\n|---|---|---|\n")
                .append("| ").append(r.resolved().size()).append(" | ").append(r.unresolved().size())
                .append(" | ").append(r.newFindings().size()).append(" |\n\n");

        sb.append(scope(r)).append("\n\n");

        list(sb, "✅ Resolved", r.resolved(), true);
        list(sb, "❌ Still open", r.unresolved(), false);
        list(sb, "🆕 New", r.newFindings(), false);
        return sb.toString();
    }

    private static String scope(Rejudge r) {
        int total = r.reevaluated().size() + r.carriedOver().size();
        if (r.fullReason() != null) {
            return "Re-evaluated all " + total + " changed file(s), because " + r.fullReason() + ".";
        }
        if (r.carriedOver().isEmpty()) {
            return "Re-evaluated all " + total + " changed file(s): each one changed since the previous review.";
        }
        return "Re-evaluated " + r.reevaluated().size() + " of " + total + " changed file(s). Unchanged since the previous "
                + "review, so their findings were carried over without running the rules again: "
                + String.join(", ", r.carriedOver().stream().map(p -> "`" + p + "`").toList()) + ".";
    }

    private static void list(StringBuilder sb, String heading, List<Finding> findings, boolean struck) {
        if (findings.isEmpty()) return;
        sb.append("#### ").append(heading).append(" (").append(findings.size()).append(")\n\n");
        for (Finding f : findings) {
            String line = "**[" + f.severity() + " · " + f.category().label() + "]** `" + f.location() + "` ("
                    + f.ruleId() + ") " + f.description();
            sb.append("- ").append(struck ? "~~" + line + "~~" : line).append('\n');
        }
        sb.append('\n');
    }

    private static String abbreviate(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
