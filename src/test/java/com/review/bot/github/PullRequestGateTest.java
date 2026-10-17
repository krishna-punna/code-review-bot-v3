package com.review.bot.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.ci.CiProperties;
import com.review.bot.config.ReviewProperties;
import com.review.bot.rejudge.RejudgeService;
import com.review.bot.review.ReviewService;
import com.review.bot.rule.RuleEngine;
import com.review.bot.rule.impl.NoSystemOutRule;
import com.review.bot.verdict.VerdictEngine;
import com.review.bot.verdict.VerdictProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHMyself;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHPullRequestReview;
import org.kohsuke.github.GHPullRequestReviewBuilder;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC7 on GitHub: every evaluated commit gets a pass/fail status that links to a summary showing
 * the verdict and the finding counts per severity.
 */
class PullRequestGateTest {

    private static final String PATH = "src/main/java/demo/Web.java";
    private static final String PR_URL = "https://github.com/acme/app/pull/7";
    private static final String REVIEW_URL = PR_URL + "#pullrequestreview-1";
    private static final String BLOCKED = "class Web {\n    void a() {\n        System.out.println(\"hi\");\n    }\n}\n";
    private static final String CLEAN = "class Web {\n    void a() {\n    }\n}\n";

    private final ReviewProperties props = new ReviewProperties("t", "s", null, List.of(), java.util.Map.of());
    private final GitHub github = mock(GitHub.class);
    private final GHRepository repo = mock(GHRepository.class);
    private final GHPullRequest pr = mock(GHPullRequest.class);

    @BeforeEach
    void pullRequest() throws Exception {
        GHCommitPointer head = mock(GHCommitPointer.class);
        GHCommitPointer base = mock(GHCommitPointer.class);
        when(github.getRepository("acme/app")).thenReturn(repo);
        when(repo.getPullRequest(7)).thenReturn(pr);
        when(pr.getHead()).thenReturn(head);
        when(pr.getBase()).thenReturn(base);
        when(head.getSha()).thenReturn("sha1");
        when(base.getSha()).thenReturn("base1");
        when(pr.getHtmlUrl()).thenReturn(new URL(PR_URL));
        GHMyself me = mock(GHMyself.class);
        when(me.getLogin()).thenReturn("review-bot");
        when(github.getMyself()).thenReturn(me);
        reviews(List.of());
    }

    private PullRequestReviewer reviewer(CiProperties ci) {
        RuleEngine engine = new RuleEngine(List.of(new NoSystemOutRule()), props);
        VerdictEngine verdicts = new VerdictEngine(VerdictProperties.defaults());
        ReviewService reviews = new ReviewService(engine, verdicts);
        return new PullRequestReviewer(github, reviews, props, new RejudgeService(reviews, engine, verdicts, props),
                new ObjectMapper(), ci);
    }

    @Test
    void failingCommit_statusLinksToTheSummary_whichShowsGateVerdictAndCounts() throws Exception {
        file(BLOCKED);
        GHPullRequestReviewBuilder review = postedReview();

        reviewer(CiProperties.defaults()).review("acme/app", 7);

        assertThat(body(review)).startsWith("> **CI gate `rule-review`: ❌ FAIL** · ⛔ BLOCK · BLOCK 0 · ERROR 1 · WARNING 0"
                + " · [details per line](" + PR_URL + "/files)");
        verify(repo).createCommitStatus(eq("sha1"), eq(GHCommitState.FAILURE), eq(REVIEW_URL),
                startsWith("BLOCK · 1 must-fix · 1 ERROR"), eq("rule-review"));
    }

    @Test
    void cleanCommit_stillGetsASummary_andAGreenStatusLinkingToIt() throws Exception {
        file(CLEAN);
        GHPullRequestReviewBuilder review = postedReview();

        reviewer(CiProperties.defaults()).review("acme/app", 7);

        assertThat(body(review)).startsWith("> **CI gate `rule-review`: ✅ PASS** · ✅ APPROVE · BLOCK 0 · ERROR 0 · WARNING 0")
                .contains("## ✅ Verdict: **APPROVE**", "APPROVE: no findings.");
        verify(repo).createCommitStatus(eq("sha1"), eq(GHCommitState.SUCCESS), eq(REVIEW_URL),
                eq("APPROVE · no findings"), eq("rule-review"));
    }

    @Test
    void summaryOnApproveOff_cleanCommitGetsOnlyTheStatus_linkingToThePullRequest() throws Exception {
        file(CLEAN);
        GHPullRequestReviewBuilder review = postedReview();

        reviewer(new CiProperties(false)).review("acme/app", 7);

        verify(review, never()).create();
        verify(repo).createCommitStatus(eq("sha1"), eq(GHCommitState.SUCCESS), eq(PR_URL),
                eq("APPROVE · no findings"), eq("rule-review"));
    }

    @Test
    void reReviewWithNothingNew_postsNothing_butTheStatusStillLinksToThePullRequest() throws Exception {
        file(BLOCKED);
        GHPullRequestReviewBuilder first = postedReview();
        PullRequestReviewer reviewer = reviewer(CiProperties.defaults());
        reviewer.review("acme/app", 7);
        String firstBody = body(first);
        GHPullRequestReview earlier = mock(GHPullRequestReview.class);
        GHUser bot = mock(GHUser.class);
        when(bot.getLogin()).thenReturn("review-bot");
        when(earlier.getUser()).thenReturn(bot);
        when(earlier.getBody()).thenReturn(firstBody);
        reviews(List.of(earlier));
        GHPullRequestReviewBuilder second = postedReview();

        reviewer.review("acme/app", 7);   // same commit again, e.g. the poller after a restart

        verify(second, never()).create();
        verify(repo).createCommitStatus(eq("sha1"), eq(GHCommitState.FAILURE), eq(PR_URL),
                startsWith("BLOCK"), eq("rule-review"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void file(String content) throws IOException {
        GHPullRequestFileDetail detail = mock(GHPullRequestFileDetail.class);
        when(detail.getFilename()).thenReturn(PATH);
        when(detail.getStatus()).thenReturn("added");
        List<String> lines = content.lines().toList();
        when(detail.getPatch()).thenReturn("@@ -0,0 +1," + lines.size() + " @@\n"
                + String.join("\n", lines.stream().map(l -> "+" + l).toList()));
        PagedIterable<GHPullRequestFileDetail> files = mock(PagedIterable.class);
        when(files.toList()).thenReturn(List.of(detail));
        when(pr.listFiles()).thenReturn(files);
        GHContent fetched = mock(GHContent.class);
        when(fetched.read()).thenAnswer(inv -> new ByteArrayInputStream(content.getBytes(UTF_8)));
        when(repo.getFileContent(PATH, "sha1")).thenReturn(fetched);
    }

    @SuppressWarnings("unchecked")
    private void reviews(List<GHPullRequestReview> reviews) throws IOException {
        PagedIterable<GHPullRequestReview> listed = mock(PagedIterable.class);
        when(listed.toList()).thenReturn(reviews);
        when(pr.listReviews()).thenReturn(listed);
    }

    private GHPullRequestReviewBuilder postedReview() throws IOException {
        GHPullRequestReviewBuilder builder = mock(GHPullRequestReviewBuilder.class, RETURNS_SELF);
        GHPullRequestReview posted = mock(GHPullRequestReview.class);
        when(posted.getHtmlUrl()).thenReturn(new URL(REVIEW_URL));
        when(builder.create()).thenReturn(posted);
        when(pr.createReview()).thenReturn(builder);
        return builder;
    }

    private static String body(GHPullRequestReviewBuilder builder) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(builder).body(body.capture());
        return body.getValue();
    }
}
