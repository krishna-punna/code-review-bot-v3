package com.review.bot.review;

import com.review.bot.review.UnifiedDiff.FilePatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * What gets reviewed, wherever it came from (a GitHub PR, the REST API, the CLI):
 * the files a change touched, each with its full new content and the lines the change added.
 */
public record ChangeSet(String title, List<ChangedFile> files) {

    /** One changed file. changedLines are 1-based lines in the new content; findings are kept only there. */
    public record ChangedFile(String path, String content, Set<Integer> changedLines) {
        public ChangedFile {
            changedLines = Set.copyOf(changedLines);
        }
    }

    public ChangeSet {
        files = List.copyOf(files);
    }

    /** Every file reviewed in full, as if the change added it. Files are ordered by path. */
    public static ChangeSet wholeFiles(String title, Map<String, String> files) {
        return new ChangeSet(title, new TreeMap<>(files).entrySet().stream()
                .map(e -> new ChangedFile(e.getKey(), e.getValue(), allLines(e.getValue())))
                .toList());
    }

    /**
     * The files of a unified diff, reported on the lines it adds. A file's new content comes from
     * contentOf; an added file may instead be rebuilt from the diff, which holds all of it.
     * Deleted files and files with no added lines are left out.
     *
     * @throws IllegalArgumentException if the diff has no file changes, a modified file has no
     *         content, or a file's content doesn't match the diff
     */
    public static ChangeSet fromDiff(String title, String diff, Function<String, Optional<String>> contentOf) {
        List<FilePatch> patches = UnifiedDiff.parse(diff);
        if (patches.isEmpty()) {
            throw new IllegalArgumentException("No file changes found in the diff. Send unified diff output, "
                    + "such as `git diff main...my-branch`.");
        }

        List<ChangedFile> files = new ArrayList<>();
        for (FilePatch patch : patches) {
            if (patch.isDeleted()) continue;
            Set<Integer> added = DiffParser.addedLinePositions(patch.hunks()).keySet();
            if (added.isEmpty()) continue;

            Map<Integer, String> newLines = DiffParser.newSideLines(patch.hunks());
            String content = contentOf.apply(patch.path())
                    .or(() -> patch.isAdded()
                            ? Optional.of(String.join("\n", newLines.values()) + "\n")
                            : Optional.empty())
                    .orElseThrow(() -> new IllegalArgumentException("No content for modified file " + patch.path()
                            + ". A diff only holds the changed hunks, so send the file's full new content too."));
            requireMatch(patch.path(), content, newLines);
            files.add(new ChangedFile(patch.path(), content, added));
        }
        return new ChangeSet(title, files);
    }

    /** Guards against a diff and file contents from different versions, which would put findings on wrong lines. */
    private static void requireMatch(String path, String content, Map<Integer, String> newLines) {
        List<String> lines = content.lines().toList();
        newLines.forEach((line, expected) -> {
            if (line > lines.size() || !lines.get(line - 1).equals(expected)) {
                throw new IllegalArgumentException("The diff doesn't match the content of " + path + " at line "
                        + line + ". Send the content of the version the diff was made against.");
            }
        });
    }

    private static Set<Integer> allLines(String content) {
        return IntStream.rangeClosed(1, (int) content.lines().count()).boxed().collect(Collectors.toSet());
    }
}
