package com.review.bot.github;

import com.review.bot.config.ReviewProperties;
import com.review.bot.rule.RuleEngine;
import com.review.bot.verdict.VerdictEngine;
import com.review.bot.verdict.VerdictProperties;
import org.junit.jupiter.api.Test;
import org.kohsuke.github.GHCommitPointer;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullRequestReviewerTest {

    private final ReviewProperties props = new ReviewProperties("t", "s", null, List.of(), Map.of());

    @Test
    void failedReview_replacesPendingWithErrorStatus() throws Exception {
        GitHub github = mock(GitHub.class);
        GHRepository repo = mock(GHRepository.class);
        GHPullRequest pr = mock(GHPullRequest.class);
        GHCommitPointer head = mock(GHCommitPointer.class);
        when(github.getRepository("acme/app")).thenReturn(repo);
        when(repo.getPullRequest(7)).thenReturn(pr);
        when(pr.getHead()).thenReturn(head);
        when(head.getSha()).thenReturn("abc1234");
        when(pr.listFiles()).thenThrow(new GHException("rate limited"));

        PullRequestReviewer reviewer = new PullRequestReviewer(github, new RuleEngine(List.of(), props),
                new VerdictEngine(VerdictProperties.defaults()), props);

        assertThatThrownBy(() -> reviewer.review("acme/app", 7)).isInstanceOf(GHException.class);
        verify(repo).createCommitStatus(eq("abc1234"), eq(GHCommitState.PENDING), isNull(), any(), eq("rule-review"));
        verify(repo).createCommitStatus(eq("abc1234"), eq(GHCommitState.ERROR), isNull(), any(), eq("rule-review"));
    }
}
