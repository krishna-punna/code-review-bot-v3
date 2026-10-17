package com.review.bot.rejudge;

import com.review.bot.config.ReviewProperties;
import com.review.bot.config.ReviewProperties.RuleConfig;
import com.review.bot.review.ChangeSet;
import com.review.bot.review.ChangeSet.ChangedFile;
import com.review.bot.review.ReviewResult;
import com.review.bot.review.ReviewService;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Rule;
import com.review.bot.rule.RuleEngine;
import com.review.bot.verdict.Verdict;
import com.review.bot.verdict.VerdictEngine;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Fix-and-rejudge (AC6): reviews a resubmitted change against its previous review.
 *
 *  - Only files that changed since the previous review are reviewed again; for unchanged files
 *    the previous findings are reused (and on GitHub the file isn't even fetched).
 *  - Each previous finding is marked resolved or still open, and findings that weren't there
 *    before are marked new.
 *  - The verdict is decided again from the findings that are open now.
 *
 * Without a previous review this is exactly {@link ReviewService}, plus the state to keep.
 */
@Service
public class RejudgeService {

    private final ReviewService reviews;
    private final VerdictEngine verdicts;
    private final String ruleset;

    public RejudgeService(ReviewService reviews, RuleEngine engine, VerdictEngine verdicts, ReviewProperties props) {
        this.reviews = reviews;
        this.verdicts = verdicts;
        this.ruleset = rulesetHash(engine, props);
    }

    /**
     * @param context   see {@link ReviewState#context()}; "" outside GitHub
     * @param commit    head commit being reviewed (GitHub), or null
     * @param previous  the previous review's state, or null for a first review
     */
    public RejudgeResult review(String title, String context, String commit, List<FileInput> files,
                                ReviewState previous) throws IOException {
        String fullReason = previous == null ? null : fullReviewReason(previous, context);
        boolean reuse = previous != null && fullReason == null;

        List<ChangedFile> toReview = new ArrayList<>();
        List<Finding> carried = new ArrayList<>();
        List<String> reevaluated = new ArrayList<>();
        List<String> carriedOver = new ArrayList<>();
        Map<String, String> keys = new TreeMap<>();
        for (FileInput file : files) {
            keys.put(file.path(), file.key());
            if (reuse && file.key().equals(previous.files().get(file.path()))) {
                carried.addAll(previous.findingsIn(file.path()));
                carriedOver.add(file.path());
            } else {
                toReview.add(file.loader().load());
                reevaluated.add(file.path());
            }
        }

        ReviewResult fresh = reviews.review(new ChangeSet(title, toReview));
        List<Finding> findings = Stream.concat(fresh.findings().stream(), carried.stream())
                .sorted(RuleEngine.ORDER)
                .toList();
        Verdict verdict = verdicts.decide(findings);
        ReviewResult result = new ReviewResult(title, files.size(), findings, verdict, !verdicts.failsGate(verdict));

        Rejudge rejudge = previous == null ? null
                : compare(previous, findings, sorted(reevaluated), sorted(carriedOver), fullReason);
        ReviewState state = new ReviewState(ReviewState.VERSION, context, commit, ruleset, verdict.decision(), keys, findings);
        return new RejudgeResult(result, rejudge, state);
    }

    private String fullReviewReason(ReviewState previous, String context) {
        if (previous.version() != ReviewState.VERSION) return "the previous review was made by another version of the bot";
        if (!ruleset.equals(previous.ruleset())) return "the rule configuration changed since the previous review";
        if (!(context == null ? "" : context).equals(previous.context())) return "the base of the change moved since the previous review";
        return null;
    }

    /** Pairs previous and current findings by fingerprint; a fingerprint seen twice pairs twice. */
    private static Rejudge compare(ReviewState previous, List<Finding> current, List<String> reevaluated,
                                   List<String> carriedOver, String fullReason) {
        Map<String, Deque<Finding>> open = new LinkedHashMap<>();
        for (Finding f : current) open.computeIfAbsent(f.fingerprint(), k -> new ArrayDeque<>()).add(f);

        List<Finding> resolved = new ArrayList<>();
        List<Finding> unresolved = new ArrayList<>();
        for (Finding before : previous.findings().stream().sorted(RuleEngine.ORDER).toList()) {
            Deque<Finding> now = open.get(before.fingerprint());
            if (now != null && !now.isEmpty()) unresolved.add(now.poll());
            else resolved.add(before);
        }
        List<Finding> newFindings = open.values().stream().flatMap(Deque::stream).sorted(RuleEngine.ORDER).toList();

        return new Rejudge(previous.decision(), previous.commit(), reevaluated, carriedOver, fullReason,
                resolved.stream().sorted(RuleEngine.ORDER).toList(),
                unresolved.stream().sorted(RuleEngine.ORDER).toList(),
                newFindings);
    }

    /** Anything that could change what the rules report. Different hash: nothing is carried over. */
    static String rulesetHash(RuleEngine engine, ReviewProperties props) {
        StringBuilder sb = new StringBuilder("state-v").append(ReviewState.VERSION);
        for (Rule rule : engine.rules()) {
            RuleConfig config = props.rules().get(rule.id());
            sb.append('\n').append(rule.id()).append(' ').append(rule.getClass().getName())
                    .append(" enabled=").append(props.isEnabled(rule.id()))
                    .append(" severity=").append(props.severityFor(rule.id(), rule.defaultSeverity()))
                    .append(" params=").append(config == null || config.params() == null ? Map.of() : new TreeMap<>(config.params()));
        }
        sb.append("\nexclude=").append(props.excludePaths());
        return FileInput.sha256(sb.toString());
    }

    private static List<String> sorted(List<String> paths) {
        return paths.stream().sorted().toList();
    }
}
