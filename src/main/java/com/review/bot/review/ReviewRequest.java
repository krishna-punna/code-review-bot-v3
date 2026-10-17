package com.review.bot.review;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A change submitted for review outside GitHub: the body of POST /api/reviews, and what the
 * CLI builds from its arguments. Both go through {@link #toChangeSet()}, so they behave the same.
 *
 * @param title  shown in the output, e.g. "Expose order lookup endpoint"
 * @param diff   unified diff of the change (`git diff` output); optional
 * @param files  path -> full new content. With a diff: the files it touches (added files may be
 *               left out, the diff holds them). Without a diff: every file is reviewed in full.
 */
public record ReviewRequest(String title, String diff, Map<String, String> files) {

    public ReviewRequest {
        title = title == null || title.isBlank() ? "Untitled change" : title.strip();
        files = files == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(files));   // sorted: stable output
    }

    /** @throws IllegalArgumentException if there is nothing to review or the input is inconsistent */
    public ChangeSet toChangeSet() {
        files.forEach((path, content) -> {
            if (content == null) throw new IllegalArgumentException("File " + path + " has no content.");
        });
        if (diff == null || diff.isBlank()) {
            if (files.isEmpty()) throw new IllegalArgumentException("Nothing to review: send a diff, files, or both.");
            return ChangeSet.wholeFiles(title, files);
        }
        return ChangeSet.fromDiff(title, diff, path -> Optional.ofNullable(files.get(path)));
    }
}
