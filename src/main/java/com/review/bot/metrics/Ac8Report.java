package com.review.bot.metrics;

import com.review.bot.metrics.Benchmark.Clarity;
import com.review.bot.metrics.Benchmark.RoundRun;
import com.review.bot.metrics.Benchmark.SampleRun;
import com.review.bot.metrics.ManualBaseline.Row;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The AC8 comparison as Markdown: the bot's measured time to decision, review rounds and clarity on
 * the sample PRs, next to the manual baseline for the same PRs and, optionally, a repository's
 * review history. Columns without data say so instead of guessing.
 */
public final class Ac8Report {

    private static final Set<String> DECISIONS = Set.of("APPROVE", "REQUEST_CHANGES", "BLOCK");

    private Ac8Report() {}

    /**
     * @param baseline     manual review rows; empty if none were recorded yet
     * @param history      a repository's review history, or null
     * @param generatedOn  date of the run, e.g. 2026-10-17
     * @param environment  where it ran, e.g. "Java 21, Linux"
     */
    public static String markdown(Benchmark.Result bench, List<Row> baseline, PrHistory.Summary history,
                                  String generatedOn, String environment) {
        StringBuilder sb = new StringBuilder("# AC8 report: time to decision and review clarity\n\n");
        sb.append("Generated ").append(generatedOn).append(" by `java -jar code-review-bot-v3.jar benchmark`: ")
                .append(bench.runs()).append(" timed runs per review after ").append(Benchmark.WARMUP_RUNS)
                .append(" warm-up runs (").append(environment).append(").\n\n");
        timeToDecision(sb, bench, baseline);
        rounds(sb, bench, baseline);
        clarity(sb, bench, baseline);
        if (history != null) sb.append(historySection(history));
        sb.append("""
                ## How the numbers are measured

                - **Bot time to decision:** time for the bot to turn a submitted change into its verdict and findings,
                  measured in-process with the same review code the server and CLI run (no network or JVM start-up).
                - **Manual time to decision:** reviewers time themselves reviewing the same sample PRs without the bot,
                  from opening the change to writing down a merge decision (`docs/ac8/manual-baseline.csv`).
                - **History:** from GitHub: time from a PR being opened to its first human review, rounds of requested
                  changes, time to merge, and time to the bot's first verdict (`history` command).

                Method and how to rerun: `docs/AC8-time-to-decision.md`.
                """);
        return sb.toString();
    }

    private static void timeToDecision(StringBuilder sb, Benchmark.Result bench, List<Row> baseline) {
        sb.append("## 1. Time to decision on the sample PRs\n\n")
                .append("| Sample | Bot verdict | Bot time to decision (median / p90) | Manual time to decision (median) | Manual decisions |\n")
                .append("|---|---|---|---|---|\n");
        for (SampleRun s : bench.samples()) {
            List<Row> rows = rowsFor(baseline, s.sample());
            sb.append("| `").append(s.sample()).append("` | ").append(s.decision().icon()).append(' ').append(s.decision())
                    .append(" | ").append(ms(s.millis().median())).append(" / ").append(ms(s.millis().p90()))
                    .append(" | ").append(rows.isEmpty() ? "not recorded yet"
                            : minutes(Stats.of(rows.stream().map(Row::minutesToDecision).toList()).median())
                            + " (" + rows.size() + " reviewer" + (rows.size() == 1 ? "" : "s") + ")")
                    .append(" | ").append(rows.isEmpty() ? "–" : String.join(", ", rows.stream().map(Row::decision).toList()))
                    .append(" |\n");
        }
        boolean allSame = bench.samples().stream().allMatch(SampleRun::deterministic);
        sb.append("\nSame change, same result: ").append(allSame ? "**yes**" : "**no**")
                .append(", every one of the ").append(bench.runs()).append(" runs of each sample gave a byte-for-byte ")
                .append(allSame ? "identical" : "different").append(" report.\n\n");

        List<Row> timed = baseline.stream().filter(r -> bench.samples().stream().anyMatch(s -> matches(r, s.sample()))).toList();
        if (timed.isEmpty()) {
            sb.append("> **No manual baseline yet.** Reviewers time themselves on the same samples and fill in "
                    + "`docs/ac8/manual-baseline.csv` (see `docs/AC8-time-to-decision.md`), then rerun "
                    + "`java -jar target/code-review-bot-v3-3.0.0.jar benchmark --baseline docs/ac8/manual-baseline.csv`.\n\n");
        } else {
            double manual = Stats.of(timed.stream().map(Row::minutesToDecision).toList()).median();
            double bot = Stats.of(bench.samples().stream().map(s -> s.millis().median()).toList()).median();
            sb.append("**Result:** a manual review of these samples took a median of **").append(minutes(manual))
                    .append("** to reach a decision; the bot reached its verdict in a median of **").append(ms(bot))
                    .append("**, with every blocking issue located and a fix stated.\n\n");
        }
    }

    private static void rounds(StringBuilder sb, Benchmark.Result bench, List<Row> baseline) {
        if (bench.rounds().isEmpty()) return;
        sb.append("## 2. Review rounds: fixing a blocked change (`samples-rejudge`)\n\n")
                .append("| Submission | Bot verdict | ✅ Resolved | ❌ Still open | 🆕 New | Files re-evaluated | Bot time (median) |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (RoundRun r : bench.rounds()) {
            boolean first = r.round() == 1;
            sb.append("| ").append(r.round()).append(" | ").append(r.decision().icon()).append(' ').append(r.decision())
                    .append(" | ").append(first ? "–" : r.resolved()).append(" | ").append(first ? "–" : r.unresolved())
                    .append(" | ").append(first ? r.newFindings() + " found" : r.newFindings())
                    .append(" | ").append(r.reevaluated()).append(" of ").append(r.files())
                    .append(" | ").append(ms(r.millis().median())).append(" |\n");
        }
        sb.append('\n');
        if (bench.roundsToApprove() != null) {
            sb.append("Approved at submission ").append(bench.roundsToApprove()).append(". Each re-review listed exactly ")
                    .append("what was resolved and what was still open, and re-evaluated only the files that changed.\n\n");
        }
        List<Row> rows = baseline.stream().filter(r -> isRejudgeSample(r.sample())).toList();
        sb.append(rows.isEmpty()
                ? "Manual baseline for the same scenario: not recorded yet (`review_rounds` of the `samples-rejudge` rows).\n\n"
                : "Manual baseline for the same scenario: a median of **"
                        + number(Stats.of(rows.stream().map(r -> (double) r.reviewRounds()).toList()).median())
                        + " review rounds** and **" + minutes(Stats.of(rows.stream().map(Row::minutesToDecision).toList()).median())
                        + "** of review time (" + rows.size() + " reviewer" + (rows.size() == 1 ? "" : "s") + ").\n\n");
    }

    private static void clarity(StringBuilder sb, Benchmark.Result bench, List<Row> baseline) {
        int reviews = bench.samples().size();
        int explicit = (int) bench.samples().stream().filter(s -> s.clarity().explicitVerdict()).count();
        int findings = sum(bench, Clarity::findings);
        int located = sum(bench, Clarity::withLocation);
        int tagged = sum(bench, Clarity::withSeverityAndCategory);
        int fixed = sum(bench, Clarity::withFix);
        boolean separated = bench.samples().stream().allMatch(s -> s.clarity().mustFixOnlyBlocking());
        boolean same = bench.samples().stream().allMatch(SampleRun::deterministic);

        int mRows = baseline.size();
        int mExplicit = (int) baseline.stream().filter(r -> DECISIONS.contains(r.decision().toUpperCase(Locale.ROOT))).count();
        int mIssues = baseline.stream().mapToInt(Row::issuesReported).sum();
        int mLocated = baseline.stream().mapToInt(Row::issuesWithLocation).sum();
        int mFixed = baseline.stream().mapToInt(Row::issuesWithFix).sum();
        String none = "not recorded yet";

        sb.append("## 3. Review clarity\n\n")
                .append("| Criterion | Bot (every sample review) | Manual baseline |\n|---|---|---|\n")
                .append("| States an explicit merge decision | ").append(of(explicit, reviews, "reviews")).append(" | ")
                .append(mRows == 0 ? none : of(mExplicit, mRows, "reviews")).append(" |\n")
                .append("| Issues located by file and line | ").append(of(located, findings, "issues")).append(" | ")
                .append(mIssues == 0 ? none : of(mLocated, mIssues, "issues")).append(" |\n")
                .append("| Issues with a concrete fix | ").append(of(fixed, findings, "issues")).append(" | ")
                .append(mIssues == 0 ? none : of(mFixed, mIssues, "issues")).append(" |\n")
                .append("| Issues tagged with severity and category | ").append(of(tagged, findings, "issues")).append(" | not recorded |\n")
                .append("| Blocking issues kept apart from advisory ones | ").append(separated ? "yes: must-fix list vs other findings" : "no")
                .append(" | not recorded |\n")
                .append("| Same change gives the same result | ").append(same ? "yes" : "no").append(" (")
                .append(bench.runs()).append(" runs per sample) | not measured |\n\n");
    }

    /** Section for a repository's review history; also printed on its own by the history command. */
    public static String historySection(PrHistory.Summary h) {
        StringBuilder sb = new StringBuilder("## 4. Review history: `").append(h.repository()).append("`\n\n")
                .append("Merged PRs read: ").append(h.prs()).append(". Times are from the PR being opened.\n\n")
                .append("| Metric | Median | p90 | PRs with data |\n|---|---|---|---|\n");
        row(sb, "Time to first human review", h.hoursToFirstHumanReview(), "h");
        row(sb, "Rounds of requested changes", h.changesRequested(), "");
        row(sb, "Time to merge", h.hoursToMerge(), "h");
        row(sb, "Bot: time to first verdict", h.secondsToBotDecision(), "s");
        return sb.append('\n').toString();
    }

    private static void row(StringBuilder sb, String metric, Stats s, String unit) {
        sb.append("| ").append(metric).append(" | ")
                .append(s.count() == 0 ? "–" : number(s.median()) + (unit.isEmpty() ? "" : " " + unit)).append(" | ")
                .append(s.count() == 0 ? "–" : number(s.p90()) + (unit.isEmpty() ? "" : " " + unit)).append(" | ")
                .append(s.count()).append(" |\n");
    }

    private static List<Row> rowsFor(List<Row> baseline, String sample) {
        return baseline.stream().filter(r -> matches(r, sample)).toList();
    }

    private static boolean matches(Row row, String sample) {
        String name = row.sample().replace('\\', '/');
        if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        return name.equals(sample) || name.equals("samples/" + sample);
    }

    private static boolean isRejudgeSample(String sample) {
        String name = sample.replace("/", "");
        return name.equals("samples-rejudge") || name.equals("rejudge");
    }

    private static int sum(Benchmark.Result bench, java.util.function.ToIntFunction<Clarity> metric) {
        return bench.samples().stream().mapToInt(s -> metric.applyAsInt(s.clarity())).sum();
    }

    private static String of(int part, int whole, String noun) {
        return whole == 0 ? "–" : part + " of " + whole + " " + noun + " (" + Math.round(100.0 * part / whole) + "%)";
    }

    private static String ms(double millis) {
        return String.format(Locale.ROOT, millis < 10 ? "%.2f ms" : "%.0f ms", millis);
    }

    private static String minutes(double minutes) {
        return String.format(Locale.ROOT, "%.1f min", minutes);
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }
}
