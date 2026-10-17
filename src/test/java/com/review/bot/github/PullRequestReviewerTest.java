package com.review.bot.github;

import com.review.bot.config.ReviewProperties;
import com.review.bot.review.ReviewService;
import com.review.bot.rule.Rule;
import com.review.bot.rule.RuleEngine;
import com.review.bot.rule.impl.NoSystemOutRule;
import com.review.bot.verdict.VerdictEngine;
import com.review.bot.verdict.VerdictProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHPullRequestReviewBuilder;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.PagedIterable;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullRequestReviewerTest {

    private static final String PATH = "src/main/java/demo/Foo.java";

    private final ReviewProperties props = new ReviewProperties("t", "s", null, List.of(), Map.of());
    private final GitHub github = mock(GitHub.class);
    private final GHRepository repo = mock(GHRepository.class);
    private final GHPullRequest pr = mock(GHPullRequest.class);

    @BeforeEach
    void pullRequestAtHeadAbc1234() throws Exception {
        GHCommitPointer head = mock(GHCommitPointer.class);
        when(github.getRepository("acme/app")).thenReturn(repo);
        when(repo.getPullRequest(7)).thenReturn(pr);
        when(pr.getHead()).thenReturn(head);
        when(head.getSha()).thenReturn("abc1234");
    }

    private PullRequestReviewer reviewer(Rule... rules) {
        return new PullRequestReviewer(github, new ReviewService(
                new RuleEngine(List.of(rules), props), new VerdictEngine(VerdictProperties.defaults())), props);
    }

    @Test
    @SuppressWarnings("unchecked")
    void review_postsOneReviewWithInlineComments_andFailsTheGate() throws Exception {
        GHPullRequestFileDetail file = mock(GHPullRequestFileDetail.class);
        when(file.getFilename()).thenReturn(PATH);
        when(file.getStatus()).thenReturn("modified");
        when(file.getPatch()).thenReturn("""
                @@ -1,4 +1,5 @@
                 class Foo {
                     void a() {
                +        System.out.println("hi");
                     }
                 }""");
        PagedIterable<GHPullRequestFileDetail> files = mock(PagedIterable.class);
        when(files.toList()).thenReturn(List.of(file));
        when(pr.listFiles()).thenReturn(files);

        GHContent content = mock(GHContent.class);
        when(content.read()).thenReturn(new ByteArrayInputStream(
                "class Foo {\n    void a() {\n        System.out.println(\"hi\");\n    }\n}\n".getBytes(UTF_8)));
        when(repo.getFileContent(PATH, "abc1234")).thenReturn(content);

        GHPullRequestReviewBuilder review = mock(GHPullRequestReviewBuilder.class, RETURNS_SELF);
        when(pr.createReview()).thenReturn(review);

        reviewer(new NoSystemOutRule()).review("acme/app", 7);

        verify(review).body(contains("Verdict: **BLOCK**"));
        verify(review).comment(contains("no-system-out"), eq(PATH), eq(3));   // diff position of new line 3
        verify(review).create();
        verify(repo).createCommitStatus(eq("abc1234"), eq(GHCommitState.FAILURE), isNull(), startsWith("BLOCK"),
                eq("rule-review"));
    }

    @Test
    void failedReview_replacesPendingWithErrorStatus() throws Exception {
        when(pr.listFiles()).thenThrow(new GHException("rate limited"));

        assertThatThrownBy(() -> reviewer().review("acme/app", 7)).isInstanceOf(GHException.class);
        verify(repo).createCommitStatus(eq("abc1234"), eq(GHCommitState.PENDING), isNull(), any(), eq("rule-review"));
        verify(repo).createCommitStatus(eq("abc1234"), eq(GHCommitState.ERROR), isNull(), any(), eq("rule-review"));
    }
}
