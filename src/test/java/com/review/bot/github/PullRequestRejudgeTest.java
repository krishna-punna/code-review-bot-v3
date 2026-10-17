package com.review.bot.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.config.ReviewProperties;
import com.review.bot.rejudge.RejudgeService;
import com.review.bot.rejudge.ReviewState;
import com.review.bot.review.ReviewService;
import com.review.bot.rule.RuleEngine;
import com.review.bot.rule.impl.NoSystemOutRule;
import com.review.bot.verdict.Decision;
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
import org.kohsuke.github.GHPullRequestReviewComment;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC6 on GitHub: a PR the bot blocked gets a fix pushed. The bot fetches and reviews only the file
 * that changed, reports what was resolved, answers the old inline comment, and updates the gate.
 */
class PullRequestRejudgeTest {

    private static final String WEB = "src/main/java/demo/Web.java";
    private static final String OTHER = "src/main/java/demo/Other.java";
    private static final String WEB_BLOCKED = "class Web {\n    void a() {\n        System.out.println(\"one\");\n    }\n}\n";
    private static final String WEB_FIXED = "class Web {\n    void a() {\n    }\n}\n";
    private static final String OTHER_BLOCKED = "class Other {\n    void b() {\n        System.out.println(\"other\");\n    }\n}\n";

    private final ReviewProperties props = new ReviewProperties("t", "s", null, List.of(), Map.of());
    private final ObjectMapper mapper = new ObjectMapper();
    private final GitHub github = mock(GitHub.class);
    private final GHRepository repo = mock(GHRepository.class);
    private final GHPullRequest pr = mock(GHPullRequest.class);
    private final GHCommitPointer head = mock(GHCommitPointer.class);
    private PullRequestReviewer reviewer;

    @BeforeEach
    void setUp() throws Exception {
        RuleEngine engine = new RuleEngine(List.of(new NoSystemOutRule()), props);
        VerdictEngine verdicts = new VerdictEngine(VerdictProperties.defaults());
        ReviewService reviews = new ReviewService(engine, verdicts);
        reviewer = new PullRequestReviewer(github, reviews, props, new RejudgeService(reviews, engine, verdicts, props), mapper);

        when(github.getRepository("acme/app")).thenReturn(repo);
        when(repo.getPullRequest(7)).thenReturn(pr);
        when(pr.getHead()).thenReturn(head);
        GHCommitPointer base = mock(GHCommitPointer.class);
        when(base.getSha()).thenReturn("base1");
        when(pr.getBase()).thenReturn(base);
        GHMyself me = mock(GHMyself.class);
        when(me.getLogin()).thenReturn("review-bot");
        when(github.getMyself()).thenReturn(me);
    }

    @Test
    void pushedFix_isRejudged_onlyTheChangedFileIsFetched_resolvedOneIsAnswered() throws Exception {
        // Round 1: both files print to System.out -> BLOCK, two inline comments, state in the review.
        commit("sha1", Map.of(WEB, WEB_BLOCKED, OTHER, OTHER_BLOCKED));
        reviews(List.of());
        GHPullRequestReviewBuilder first = newReview();

        reviewer.review("acme/app", 7);

        String firstBody = captured(first);
        assertThat(ReviewStateMarker.decode(firstBody, mapper)).get()
                .extracting(ReviewState::decision).isEqualTo(Decision.BLOCK);
        ArgumentCaptor<String> comments = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
        verify(first, org.mockito.Mockito.times(2)).comment(comments.capture(), paths.capture(), anyInt());
        assertThat(comments.getAllValues()).allSatisfy(c -> assertThat(ReviewStateMarker.fingerprintIn(c)).isNotNull());
        Map<String, String> commentOn = Map.of(paths.getAllValues().get(0), comments.getAllValues().get(0),
                paths.getAllValues().get(1), comments.getAllValues().get(1));

        // Round 2: the developer fixes Web.java only.
        commit("sha2", Map.of(WEB, WEB_FIXED, OTHER, OTHER_BLOCKED));
        reviews(List.of(review("review-bot", firstBody)));
        GHPullRequestReviewComment webComment = inlineComment("review-bot", commentOn.get(WEB));
        GHPullRequestReviewComment otherComment = inlineComment("review-bot", commentOn.get(OTHER));
        reviewComments(List.of(webComment, otherComment));
        GHPullRequestReviewBuilder second = newReview();

        reviewer.review("acme/app", 7);

        verify(repo).getFileContent(WEB, "sha2");
        verify(repo, never()).getFileContent(OTHER, "sha2");              // unchanged: findings carried over
        String body = captured(second);
        assertThat(body).contains("Since the previous review", "✅ Resolved (1)", "❌ Still open (1)",
                "Re-evaluated 1 of 2 changed file(s)", "Verdict: ⛔ BLOCK → ⛔ BLOCK", "(previous review: `sha1`)");
        assertThat(ReviewStateMarker.decode(body, mapper)).get()
                .extracting(s -> s.findings().size()).isEqualTo(1);
        verify(second, never()).comment(anyString(), anyString(), anyInt());   // no new findings, no new comments
        verify(webComment).reply(startsWith("✅ Resolved in `sha2`"));
        verify(otherComment, never()).reply(anyString());
        verify(repo).createCommitStatus(eq("sha2"), eq(GHCommitState.FAILURE), isNull(), startsWith("BLOCK"), eq("rule-review"));
    }

    @Test
    void everythingFixed_postsTheResolvedReview_andTurnsTheGateGreen() throws Exception {
        commit("sha1", Map.of(WEB, WEB_BLOCKED));
        reviews(List.of());
        GHPullRequestReviewBuilder first = newReview();
        reviewer.review("acme/app", 7);

        commit("sha2", Map.of(WEB, WEB_FIXED));
        reviews(List.of(review("review-bot", captured(first))));
        reviewComments(List.of());
        GHPullRequestReviewBuilder second = newReview();
        reviewer.review("acme/app", 7);

        assertThat(captured(second)).contains("Verdict: ⛔ BLOCK → ✅ APPROVE", "✅ Resolved (1)");
        verify(repo).createCommitStatus(eq("sha2"), eq(GHCommitState.SUCCESS), isNull(), startsWith("APPROVE"), eq("rule-review"));
    }

    @Test
    void stateInSomeoneElsesReview_isIgnored_soEveryFileIsReviewedAgain() throws Exception {
        commit("sha1", Map.of(WEB, WEB_BLOCKED, OTHER, OTHER_BLOCKED));
        reviews(List.of());
        GHPullRequestReviewBuilder first = newReview();
        reviewer.review("acme/app", 7);

        // Someone copies the bot's state into their own review, hoping to have findings carried over.
        commit("sha2", Map.of(WEB, WEB_FIXED, OTHER, OTHER_BLOCKED));
        reviews(List.of(review("mallory", captured(first))));
        GHPullRequestReviewBuilder second = newReview();
        reviewer.review("acme/app", 7);

        verify(repo).getFileContent(OTHER, "sha2");
        assertThat(captured(second)).doesNotContain("Since the previous review");
    }

    @Test
    void sameCommitAgain_postsNothingNew_butStillSetsTheGate() throws Exception {
        commit("sha1", Map.of(WEB, WEB_BLOCKED));
        reviews(List.of());
        GHPullRequestReviewBuilder first = newReview();
        reviewer.review("acme/app", 7);

        reviews(List.of(review("review-bot", captured(first))));
        GHPullRequestReviewBuilder second = newReview();
        reviewer.review("acme/app", 7);

        verify(second, never()).create();
        verify(repo, org.mockito.Mockito.times(2))
                .createCommitStatus(eq("sha1"), eq(GHCommitState.FAILURE), isNull(), any(), eq("rule-review"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** The PR's head is now `sha`, adding exactly these files. */
    @SuppressWarnings("unchecked")
    private void commit(String sha, Map<String, String> files) throws IOException {
        when(head.getSha()).thenReturn(sha);
        List<GHPullRequestFileDetail> details = new ArrayList<>();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(files).entrySet()) {
            GHPullRequestFileDetail d = mock(GHPullRequestFileDetail.class);
            when(d.getFilename()).thenReturn(e.getKey());
            when(d.getStatus()).thenReturn("added");
            when(d.getPatch()).thenReturn(addedFilePatch(e.getValue()));
            details.add(d);
            GHContent content = mock(GHContent.class);
            when(content.read()).thenAnswer(inv -> new ByteArrayInputStream(e.getValue().getBytes(UTF_8)));
            when(repo.getFileContent(e.getKey(), sha)).thenReturn(content);
        }
        PagedIterable<GHPullRequestFileDetail> listed = mock(PagedIterable.class);
        when(listed.toList()).thenReturn(details);
        when(pr.listFiles()).thenReturn(listed);
    }

    private static String addedFilePatch(String content) {
        List<String> lines = content.lines().toList();
        return "@@ -0,0 +1," + lines.size() + " @@\n" + String.join("\n", lines.stream().map(l -> "+" + l).toList());
    }

    @SuppressWarnings("unchecked")
    private void reviews(List<GHPullRequestReview> reviews) throws IOException {
        PagedIterable<GHPullRequestReview> listed = mock(PagedIterable.class);
        when(listed.toList()).thenReturn(reviews);
        when(pr.listReviews()).thenReturn(listed);
    }

    @SuppressWarnings("unchecked")
    private void reviewComments(List<GHPullRequestReviewComment> comments) throws IOException {
        PagedIterable<GHPullRequestReviewComment> listed = mock(PagedIterable.class);
        when(listed.toList()).thenReturn(comments);
        when(pr.listReviewComments()).thenReturn(listed);
    }

    private GHPullRequestReviewBuilder newReview() {
        GHPullRequestReviewBuilder builder = mock(GHPullRequestReviewBuilder.class, RETURNS_SELF);
        when(pr.createReview()).thenReturn(builder);
        return builder;
    }

    private static String captured(GHPullRequestReviewBuilder builder) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(builder).body(body.capture());
        return body.getValue();
    }

    private static GHPullRequestReview review(String login, String body) throws IOException {
        GHPullRequestReview review = mock(GHPullRequestReview.class);
        GHUser author = user(login);
        when(review.getUser()).thenReturn(author);
        when(review.getBody()).thenReturn(body);
        return review;
    }

    private static GHPullRequestReviewComment inlineComment(String login, String body) throws IOException {
        GHPullRequestReviewComment comment = mock(GHPullRequestReviewComment.class);
        GHUser author = user(login);
        when(comment.getUser()).thenReturn(author);
        when(comment.getBody()).thenReturn(body);
        when(comment.getInReplyToId()).thenReturn(0L);
        return comment;
    }

    private static GHUser user(String login) {
        GHUser user = mock(GHUser.class);
        when(user.getLogin()).thenReturn(login);
        return user;
    }
}
