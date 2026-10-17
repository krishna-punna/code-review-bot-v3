package com.review.bot.review;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Splits a multi-file unified diff (`git diff` output, or plain `diff -u`) into one patch per file.
 *
 * Each patch keeps only its hunks, the same shape the GitHub PR files API returns, so
 * {@link DiffParser} works on it unchanged. Hunks are read by their line counts, so a removed
 * line that happens to start with "-- " is never mistaken for the next file's header.
 * Files without hunks (binary, mode-only changes, pure renames) are left out.
 */
public final class UnifiedDiff {

    /** One file of the diff. A null oldPath means the file was added; a null newPath means deleted. */
    public record FilePatch(String oldPath, String newPath, String hunks) {
        public String path() { return newPath != null ? newPath : oldPath; }
        public boolean isAdded() { return oldPath == null; }
        public boolean isDeleted() { return newPath == null; }
    }

    private UnifiedDiff() {}

    public static List<FilePatch> parse(String diff) {
        List<FilePatch> patches = new ArrayList<>();
        List<String> lines = diff.lines().toList();   // also drops the \r of CRLF diffs
        Section section = null;

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.startsWith("diff --git ")) {
                add(patches, section);
                section = new Section();
                i++;
            } else if (line.startsWith("--- ") && i + 1 < lines.size() && lines.get(i + 1).startsWith("+++ ")) {
                if (section == null || section.hunks.length() > 0) {   // plain `diff -u` has no "diff --git" line
                    add(patches, section);
                    section = new Section();
                }
                section.oldPath = path(line.substring(4), "a/");
                section.newPath = path(lines.get(i + 1).substring(4), "b/");
                section.hasPaths = true;
                i += 2;
            } else if (section != null && section.hasPaths && DiffParser.HUNK.matcher(line).matches()) {
                i = readHunk(lines, i, section.hunks);
            } else {
                i++;   // index, mode and rename lines, "Binary files ... differ", commit headers
            }
        }
        add(patches, section);
        return patches;
    }

    /** Copies one hunk, using its line counts to find where it ends. Returns the index after it. */
    private static int readHunk(List<String> lines, int start, StringBuilder out) {
        Matcher m = DiffParser.HUNK.matcher(lines.get(start));
        m.matches();
        int oldLeft = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int newLeft = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));

        if (out.length() > 0) out.append('\n');
        out.append(lines.get(start));
        int i = start + 1;
        while (i < lines.size() && (oldLeft > 0 || newLeft > 0 || lines.get(i).startsWith("\\"))) {
            String line = lines.get(i);
            if (line.startsWith("+")) {
                newLeft--;
            } else if (line.startsWith("-")) {
                oldLeft--;
            } else if (!line.startsWith("\\")) {   // context; some tools drop the space of blank context lines
                oldLeft--;
                newLeft--;
            }
            out.append('\n').append(line);
            i++;
        }
        return i;
    }

    private static void add(List<FilePatch> patches, Section s) {
        if (s != null && s.hasPaths && s.hunks.length() > 0 && s.path() != null) {
            patches.add(new FilePatch(s.oldPath, s.newPath, s.hunks.toString()));
        }
    }

    /** "a/src/Foo.java", "\"b/my file.txt\"" or "src/Foo.java\t2024-01-01 ..." -> repo path; /dev/null -> null. */
    private static String path(String raw, String prefix) {
        String p = raw;
        int tab = p.indexOf('\t');   // `diff -u` appends a timestamp after a tab
        if (tab >= 0) p = p.substring(0, tab);
        p = p.strip();
        if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
        if (p.equals("/dev/null")) return null;
        return p.startsWith(prefix) ? p.substring(prefix.length()) : p;
    }

    private static final class Section {
        String oldPath;
        String newPath;
        boolean hasPaths;
        final StringBuilder hunks = new StringBuilder();

        String path() { return newPath != null ? newPath : oldPath; }
    }
}
