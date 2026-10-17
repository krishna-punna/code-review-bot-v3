package com.review.bot.github;

import com.review.bot.config.ReviewProperties;
import com.review.bot.review.ChangeSet;
import com.review.bot.review.ChangeSet.ChangedFile;
import com.review.bot.review.DiffParser;
import com.review.bot.review.ReviewResult;
import com.review.bot.review.ReviewService;
import com.review.bot.rule.Finding;
import com.review.bot.rule.RuleEngine;
import com.review.bot.verdict.Verdict;
import com.review.bot.verdict.VerdictFormatter;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub adapter for {@link ReviewService}. Fetches a PR's changed files, reviews them, then:
 *  1. posts one review: verdict, must-fix list, counts, and inline comments, and
 *  2. sets the commit status (the CI gate) from the verdict.
 */
@Service
public class PullRequestReviewer {

    private static final Logger log = LoggerFactory.getLogger(PullRequestReviewer.class);
    private static final int MAX_INLINE_COMMENTS = 50;

    private final GitHub github;
    private final ReviewService reviews;
    private final ReviewProperties props;

    public PullRequestReviewer(GitHub github, ReviewService reviews, ReviewProperties props) {
        this.github = github;
        this.reviews = reviews;
        this.props = props;
    }

    public Verdict review(String repoFullName, int prNumber) throws IOException {
        GHRepository repo = github.getRepository(repoFullName);
        GHPullRequest pr = repo.getPullRequest(prNumber);
        String headSha = pr.getHead().getSha();

        repo.createCommitStatus(headSha, GHCommitState.PENDING, null, "Running rules…", props.statusContext());
        try {
            return runReview(repo, pr, headSha, repoFullName, prNumber);
        } catch (IOException | RuntimeException e) {
            // Never leave the status PENDING: a required check would block the merge forever.
            markFailed(repo, headSha, e);
            throw e;
        }
    }

    private Verdict runReview(GHRepository repo, GHPullRequest pr, String headSha,
                              String repoFullName, int prNumber) throws IOException {
        List<ChangedFile> files = new ArrayList<>();
        Map<String, Map<Integer, Integer>> positions = new HashMap<>();   // path -> new line -> diff position
        for (GHPullRequestFileDetail f : pr.listFiles().toList()) {
            if ("removed".equals(f.getStatus()) || f.getPatch() == null) continue;  // binary or deleted

            Map<Integer, Integer> filePositions = DiffParser.addedLinePositions(f.getPatch());
            if (filePositions.isEmpty()) continue;

            files.add(new ChangedFile(f.getFilename(), readFile(repo, f.getFilename(), headSha), filePositions.keySet()));
            positions.put(f.getFilename(), filePositions);
        }

        ReviewResult result = reviews.review(new ChangeSet(repoFullName + "#" + prNumber, files));
        List<PositionedFinding> positioned = result.findings().stream()
                .map(finding -> new PositionedFinding(finding, positions.get(finding.path()).get(finding.line())))
                .toList();

        Verdict verdict = result.verdict();
        postReview(pr, headSha, positioned, verdict);
        setStatus(repo, headSha, result);
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

    private void setStatus(GHRepository repo, String sha, ReviewResult result) throws IOException {
        GHCommitState state = result.gatePassed() ? GHCommitState.SUCCESS : GHCommitState.FAILURE;
        repo.createCommitStatus(sha, state, null, VerdictFormatter.statusLine(result.verdict()), props.statusContext());
    }

    private void markFailed(GHRepository repo, String sha, Exception cause) {
        try {
            repo.createCommitStatus(sha, GHCommitState.ERROR, null,
                    "Review failed (" + cause.getClass().getSimpleName() + "); see the bot's logs.",
                    props.statusContext());
        } catch (IOException | RuntimeException e) {
            cause.addSuppressed(e);   // keep the original failure as the one that gets logged
        }
    }

    private static String abbreviate(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private record PositionedFinding(Finding finding, int position) {}
}
