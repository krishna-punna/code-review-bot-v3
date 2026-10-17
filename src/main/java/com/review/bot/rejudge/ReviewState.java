package com.review.bot.rejudge;

import com.review.bot.rule.Finding;
import com.review.bot.verdict.Decision;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a review leaves behind so the next one can re-judge the change (AC6): which version of
 * each file was reviewed, and what was found in it.
 *
 * @param version   format version; a state from another version leads to a full re-review
 * @param context   where the change lives: "github:&lt;base sha&gt;" on GitHub, "" for the CLI and API.
 *                  Findings are only carried over while it stays the same.
 * @param commit    the head commit that was reviewed (GitHub), or null
 * @param ruleset   hash of the rule configuration the findings came from
 * @param decision  the verdict of that review
 * @param files     path -> key of the reviewed input; the same key means the same findings
 * @param findings  every finding of that review, most severe first
 */
public record ReviewState(int version, String context, String commit, String ruleset, Decision decision,
                          Map<String, String> files, List<Finding> findings) {

    public static final int VERSION = 1;

    public ReviewState {
        context = context == null ? "" : context;
        files = files == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(files));   // sorted: stable output
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    List<Finding> findingsIn(String path) {
        return findings.stream().filter(f -> f.path().equals(path)).toList();
    }
}
