package com.review.bot.review;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified-diff "patch" (as returned by the GitHub PR files API) into a map of
 *   new-file line number -> diff position
 * for every added line.
 *
 * Diff position is what GitHub's review-comment API expects: the line just below the
 * first "@@" header is position 1, and every later line (including later "@@" headers)
 * increments it.
 */
public final class DiffParser {

    /** Groups: 1 old start, 2 old count, 3 new start, 4 new count (a missing count means 1). */
    static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private DiffParser() {}

    public static Map<Integer, Integer> addedLinePositions(String patch) {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        if (patch == null || patch.isEmpty()) return result;

        int position = 0;
        int newLine = 0;
        boolean firstHunkSeen = false;

        for (String line : patch.split("\n", -1)) {
            Matcher m = HUNK.matcher(line);
            if (m.matches()) {
                if (firstHunkSeen) position++;   // later hunk headers count as a position
                firstHunkSeen = true;
                newLine = Integer.parseInt(m.group(3));
                continue;
            }
            if (!firstHunkSeen) continue;
            position++;

            if (line.startsWith("+")) {
                result.put(newLine, position);
                newLine++;
            } else if (line.startsWith("-")) {
                // removed line: exists only in old file
            } else if (line.startsWith("\\")) {
                // "\ No newline at end of file"
            } else {
                newLine++;                       // context line
            }
        }
        return result;
    }

    /** Text of every line on the new side of the patch (added and context lines), by new line number. */
    public static Map<Integer, String> newSideLines(String patch) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (patch == null || patch.isEmpty()) return result;

        int newLine = 0;
        boolean firstHunkSeen = false;
        for (String line : patch.split("\n", -1)) {
            Matcher m = HUNK.matcher(line);
            if (m.matches()) {
                newLine = Integer.parseInt(m.group(3));
                firstHunkSeen = true;
            } else if (firstHunkSeen && !line.startsWith("-") && !line.startsWith("\\")) {
                result.put(newLine++, line.isEmpty() ? "" : line.substring(1));   // "" = blank context line
            }
        }
        return result;
    }
}
