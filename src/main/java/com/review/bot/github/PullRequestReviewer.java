package com.review.bot.github;

import com.review.bot.config.ReviewProperties;
import com.review.bot.rule.FileContext;
import com.review.bot.rule.Finding;
import com.review.bot.rule.RuleEngine;
import com.review.bot.verdict.Verdict;
import com.review.bot.verdict.VerdictEngine;
import com.review.bot.verdict.VerdictFormatter;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fetches a PR's changed files, runs the rule engine, computes the verdict, then:
 *  1. posts one review: verdict, must-fix list, counts, and inline comments, and
 *  2. sets the commit status (the CI gate) from the verdict.
 */
@Service
public class PullRequestReviewer {

    private static final Logger log = LoggerFactory.getLogger(PullRequestReviewer.class);
    private static final int MAX_INLINE_COMMENTS = 50;

    private final GitHub github;
    private final RuleEngine engine;
    private final VerdictEngine verdicts;
    private final ReviewProperties props;

    public PullRequestReviewer(GitHub github, RuleEngine engine, VerdictEngine verdicts, ReviewProperties props) {
        this.github = github;
        this.engine = engine;
        this.verdicts = verdicts;
        this.props = props;
    }

    public Verdict review(String repoFullName, int prNumber) throws IOException {
        GHRepository repo = github.getRepository(repoFullName);
        GHPullRequest pr = repo.getPullRequest(prNumber);
        String headSha = pr.getHead().getSha();

        repo.createCommitStatus(headSha, GHCommitState.PENDING, null, "Running rules…", props.statusContext());

        List<PositionedFinding> results = new ArrayList<>();
        for (GHPullRequestFileDetail f : pr.listFiles()) {
            if ("removed".equals(f.getStatus()) || f.getPatch() == null) continue;  // binary or deleted

            Map<Integer, Integer> positions = DiffParser.addedLinePositions(f.getPatch());
            if (positions.isEmpty()) continue;

            String content = readFile(repo, f.getFilename(), headSha);
            FileContext ctx = new FileContext(f.getFilename(), content, positions.keySet());

            for (Finding finding : engine.review(ctx)) {
                results.add(new PositionedFinding(finding, positions.get(finding.line())));
            }
        }

        Verdict verdict = verdicts.decide(results.stream().map(PositionedFinding::finding).toList());
        postReview(pr, headSha, results, verdict);
        setStatus(repo, headSha, verdict);
        log.info("Reviewed {}#{} @ {}: {}", repoFullName, prNumber, abbreviate(headSha), verdict.reason());
        return verdict;
    }

    private String readFile(GHRepository repo, String path, String sha) throws IOException {
        try (InputStream in = repo.getFileContent(path, sha).read()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void postReview(GHPullRequest pr, String sha, List<PositionedFinding> results,
                            Verdict verdict) throws IOException {
        if (results.isEmpty()) return;   // clean PR: the green APPROVE status is enough, no comment noise

        // Always posted as a COMMENT review. A PAT cannot APPROVE / REQUEST_CHANGES on its owner's
        // own PRs (GitHub returns 422), and the CI gate is what enforces the verdict.
        GHPullRequestReviewBuilder review = pr.createReview()
                .commitId(sha)
                .event(GHPullRequestReviewEvent.COMMENT)
                .body(VerdictFormatter.markdown(verdict, MAX_INLINE_COMMENTS));

        // Inline comments in verdict order: must-fix items first.
        results.stream()
                .sorted((a, b) -> RuleEngine.ORDER.compare(a.finding(), b.finding()))
                .limit(MAX_INLINE_COMMENTS)
                .forEach(r -> review.comment(r.finding().toMarkdown(), r.finding().path(), r.position()));

        review.create();
    }

    private void setStatus(GHRepository repo, String sha, Verdict verdict) throws IOException {
        GHCommitState state = verdicts.failsGate(verdict) ? GHCommitState.FAILURE : GHCommitState.SUCCESS;
        repo.createCommitStatus(sha, state, null, VerdictFormatter.statusLine(verdict), props.statusContext());
    }

    private static String abbreviate(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private record PositionedFinding(Finding finding, int position) {}
}
