package com.review.bot.metrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The manual review baseline (AC8): reviewers time themselves reviewing the same sample PRs
 * without the bot and record what they found, one CSV row per reviewer and sample.
 * Lines starting with # are comments. See docs/ac8/manual-baseline.csv.
 */
public final class ManualBaseline {

    public static final List<String> COLUMNS = List.of("sample", "reviewer", "minutes_to_decision", "decision",
            "issues_reported", "issues_with_location", "issues_with_fix", "review_rounds");

    /**
     * @param minutesToDecision     from opening the PR to writing down a merge decision
     * @param decision              the reviewer's decision, e.g. BLOCK
     * @param issuesReported        problems the reviewer wrote down
     * @param issuesWithLocation    of those, how many named the file and line
     * @param issuesWithFix         of those, how many said what to change
     * @param reviewRounds          review rounds until approval (the rejudge sample); 1 for the others
     */
    public record Row(String sample, String reviewer, double minutesToDecision, String decision, int issuesReported,
                      int issuesWithLocation, int issuesWithFix, int reviewRounds) {}

    private ManualBaseline() {}

    public static List<Row> read(Path csv) throws IOException {
        List<Row> rows = new ArrayList<>();
        List<String> header = null;
        int number = 0;
        for (String raw : Files.readAllLines(csv)) {
            number++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            List<String> cells = Arrays.stream(line.split(",", -1)).map(String::strip).toList();
            if (header == null) {
                header = cells;
                List<String> missing = COLUMNS.stream().filter(c -> !cells.contains(c)).toList();
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException(csv + ": the header line lacks the column(s) " + missing);
                }
                continue;
            }
            if (cells.size() != header.size()) {
                throw new IllegalArgumentException(csv + " line " + number + ": expected " + header.size()
                        + " values, found " + cells.size());
            }
            rows.add(row(header, cells, csv, number));
        }
        if (header == null) throw new IllegalArgumentException(csv + ": no header line");
        return rows;
    }

    private static Row row(List<String> header, List<String> cells, Path csv, int number) {
        String at = csv + " line " + number + ": ";
        try {
            return new Row(cell(header, cells, "sample"), cell(header, cells, "reviewer"),
                    Double.parseDouble(cell(header, cells, "minutes_to_decision")), cell(header, cells, "decision"),
                    Integer.parseInt(cell(header, cells, "issues_reported")),
                    Integer.parseInt(cell(header, cells, "issues_with_location")),
                    Integer.parseInt(cell(header, cells, "issues_with_fix")),
                    Integer.parseInt(cell(header, cells, "review_rounds")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(at + "not a number (" + e.getMessage() + ")");
        }
    }

    private static String cell(List<String> header, List<String> cells, String column) {
        return cells.get(header.indexOf(column));
    }
}
