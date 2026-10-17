package com.review.bot.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.config.ReviewProperties;
import com.review.bot.rejudge.FileInput;
import com.review.bot.rejudge.Rejudge;
import com.review.bot.rejudge.RejudgeFormatter;
import com.review.bot.rejudge.RejudgeResult;
import com.review.bot.rejudge.RejudgeService;
import com.review.bot.rejudge.ReviewState;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GitHub adapter for {@link ReviewService}. Fetches a PR's changed files, reviews them, then:
 *  1. posts one review: verdict, must-fix list, counts, and inline comments, and
 *  2. sets the commit status (the CI gate) from the verdict.
 *
 * With a {@link RejudgeService} (the normal setup), a PR the bot reviewed before is re-judged
 * against that review (AC6): only files whose patch changed are fetched and reviewed again, the
 * review lists what was resolved, what is still open and what is new, and the earlier inline
 * comment of each resolved finding gets a "resolved" reply. The state for this lives in the
 * bot's own review, and only reviews written by the bot's account are trusted.
 */
@Service
public class PullRequestReviewer {

    private static final Logger log = LoggerFactory.getLogger(PullRequestReviewer.class);
    private static final int MAX_INLINE_COMMENTS = 50;

    private final GitHub github;
    private final ReviewService reviews;
    private final ReviewProperties props;
    private final RejudgeService rejudge;   // null: every review is a full first review, no state kept
    private final ObjectMapper mapper;
    private volatile String botLogin;

    /** Reviews every PR in full each time, keeping no state. */
    public PullRequestReviewer(GitHub github, ReviewService reviews, ReviewProperties props) {
        this(github, reviews, props, null, null);
    }

    @Autowired
    public PullRequestReviewer(GitHub github, ReviewService reviews, ReviewProperties props,
                               RejudgeService rejudge, ObjectMapper mapper) {
        this.github = github;
        this.reviews = reviews;
        this.props = props;
        this.rejudge = rejudge;
        this.mapper = mapper;
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
        List<FileInput> files = new ArrayList<>();
        Map<String, Map<Integer, Integer>> positions = new HashMap<>();   // path -> new line -> diff position
        for (GHPullRequestFileDetail f : pr.listFiles().toList()) {
            if ("removed".equals(f.getStatus()) || f.getPatch() == null) continue;  // binary or deleted

            Map<Integer, Integer> filePositions = DiffParser.addedLinePositions(f.getPatch());
            if (filePositions.isEmpty()) continue;

            // Fetched only when the file is reviewed: on a re-review, only if its patch changed.
            String path = f.getFilename();
            files.add(new FileInput(path, FileInput.patchKey(path, f.getPatch()),
                    () -> new ChangedFile(path, readFile(repo, path, headSha), filePositions.keySet())));
            positions.put(path, filePositions);
        }

        String title = repoFullName + "#" + prNumber;
        RejudgeResult rejudged = rejudge == null
                ? new RejudgeResult(reviews.review(new ChangeSet(title, load(files))), null, null)
                : rejudge.review(title, "github:" + pr.getBase().getSha(), headSha, files, previousState(pr));
        ReviewResult result = rejudged.result();
        List<PositionedFinding> positioned = result.findings().stream()
                .filter(finding -> position(positions, finding) != null)   // always set, unless a stored state is inconsistent
                .map(finding -> new PositionedFinding(finding, position(positions, finding)))
                .toList();

        Verdict verdict = result.verdict();
        postReview(pr, headSha, positioned, rejudged);
        setStatus(repo, headSha, result);
        log.info("Reviewed {}#{} @ {}: {}", repoFullName, prNumber, abbreviate(headSha), verdict.reason());
        Rejudge change = rejudged.rejudge();
        if (change != null) {
            log.info("Re-review of {}: {} resolved, {} still open, {} new; re-evaluated {} of {} file(s)", title,
                    change.resolved().size(), change.unresolved().size(), change.newFindings().size(),
                    change.reevaluated().size(), files.size());
        }
        return verdict;
    }

    private static List<ChangedFile> load(List<FileInput> files) throws IOException {
        List<ChangedFile> loaded = new ArrayList<>();
        for (FileInput file : files) loaded.add(file.loader().load());
        return loaded;
    }

    private static Integer position(Map<String, Map<Integer, Integer>> positions, Finding finding) {
        return positions.getOrDefault(finding.path(), Map.of()).get(finding.line());
    }

    /** The state in the bot's latest review of this PR, or null. Reviews by other accounts are ignored. */
    private ReviewState previousState(GHPullRequest pr) {
        try {
            String me = botLogin();
            ReviewState latest = null;
            for (GHPullRequestReview review : pr.listReviews().toList()) {   // oldest first
                GHUser author = review.getUser();
                if (author == null || !me.equals(author.getLogin())) continue;
                Optional<ReviewState> state = ReviewStateMarker.decode(review.getBody(), mapper);
                if (state.isPresent()) latest = state.get();
            }
            return latest;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read earlier reviews of PR #{}; reviewing every file: {}", pr.getNumber(), e.getMessage());
            return null;
        }
    }

    private String botLogin() throws IOException {
        if (botLogin == null) botLogin = github.getMyself().getLogin();
        return botLogin;
    }

    private String readFile(GHRepository repo, String path, String sha) throws IOException {
        try (InputStream in = repo.getFileContent(path, sha).read()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void postReview(GHPullRequest pr, String sha, List<PositionedFinding> results,
                            RejudgeResult rejudged) throws IOException {
        Rejudge change = rejudged.rejudge();
        if (change == null && results.isEmpty()) return;   // clean PR: the green APPROVE status is enough, no comment noise
        if (change != null && change.resolved().isEmpty() && change.newFindings().isEmpty()
                && change.previousDecision() == rejudged.result().verdict().decision()) {
            return;   // nothing changed since the previous review, which still says it all
        }

        // Always posted as a COMMENT review. A PAT cannot APPROVE / REQUEST_CHANGES on its owner's
        // own PRs (GitHub returns 422), and the CI gate is what enforces the verdict.
        String body = VerdictFormatter.markdown(rejudged.result().verdict(), MAX_INLINE_COMMENTS);
        if (change != null) body = RejudgeFormatter.section(change, rejudged.result()) + "\n" + body;
        if (rejudged.state() != null) body += ReviewStateMarker.encode(rejudged.state(), mapper, body.length());
        GHPullRequestReviewBuilder review = pr.createReview()
                .commitId(sha)
                .event(GHPullRequestReviewEvent.COMMENT)
                .body(body);

        // Inline comments in verdict order: must-fix items first. On a re-review only new findings
        // get one; findings still open keep their comment from the earlier review.
        Set<Finding> commented = Collections.newSetFromMap(new IdentityHashMap<>());
        if (change != null) commented.addAll(change.newFindings());
        results.stream()
                .filter(r -> change == null || commented.contains(r.finding()))
                .sorted((a, b) -> RuleEngine.ORDER.compare(a.finding(), b.finding()))
                .limit(MAX_INLINE_COMMENTS)
                .forEach(r -> review.comment(commentBody(r.finding()), r.finding().path(), r.position()));

        review.create();
        if (change != null) markResolved(pr, sha, change.resolved());
    }

    private String commentBody(Finding finding) {
        return rejudge == null ? finding.toMarkdown()
                : finding.toMarkdown() + ReviewStateMarker.fingerprintMarker(finding.fingerprint());
    }

    /** Replies "resolved" under the latest inline comment of each finding that is gone. */
    private void markResolved(GHPullRequest pr, String sha, List<Finding> resolved) {
        if (resolved.isEmpty()) return;
        Map<String, Integer> left = new HashMap<>();
        resolved.forEach(f -> left.merge(f.fingerprint(), 1, Integer::sum));
        try {
            String me = botLogin();
            List<GHPullRequestReviewComment> comments = new ArrayList<>(pr.listReviewComments().toList());
            Collections.reverse(comments);   // newest first
            int replies = 0;
            for (GHPullRequestReviewComment comment : comments) {
                if (replies >= MAX_INLINE_COMMENTS) break;
                String fingerprint = comment.getInReplyToId() == 0 ? ReviewStateMarker.fingerprintIn(comment.getBody()) : null;
                if (fingerprint == null || left.getOrDefault(fingerprint, 0) == 0) continue;
                GHUser author = comment.getUser();
                if (author == null || !me.equals(author.getLogin())) continue;

                comment.reply("✅ Resolved in `" + abbreviate(sha) + "`: the bot no longer finds this.");
                left.merge(fingerprint, -1, Integer::sum);
                replies++;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not reply to resolved findings on PR #{}: {}", pr.getNumber(), e.getMessage());
        }
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
