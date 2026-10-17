package com.review.bot.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.rejudge.ReviewState;
import com.review.bot.rule.Category;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Severity;
import com.review.bot.verdict.Decision;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewStateMarkerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final ReviewState state = new ReviewState(ReviewState.VERSION, "github:base1", "sha1", "rules1",
            Decision.BLOCK, Map.of("src/A.java", "key1"),
            List.of(new Finding("no-system-out", Category.QUALITY, Severity.ERROR, "src/A.java", 3,
                    "Console output.", "Use a logger.", "0123456789abcdef")));

    @Test
    void stateSurvivesTheRoundTripThroughAReviewBody() {
        String body = "## ⛔ Verdict: **BLOCK**\n" + ReviewStateMarker.encode(state, mapper, 100);

        assertThat(ReviewStateMarker.decode(body, mapper)).contains(state);
    }

    @Test
    void markerIsAnHtmlCommentGitHubDoesNotDisplay() {
        assertThat(ReviewStateMarker.encode(state, mapper, 0).strip())
                .startsWith("<!-- rule-review-state:v1:").endsWith(" -->");
    }

    @Test
    void stateThatWouldNotFitInTheReviewIsLeftOut() {
        assertThat(ReviewStateMarker.encode(state, mapper, ReviewStateMarker.MAX_BODY)).isEmpty();
    }

    @Test
    void bodiesWithoutAValidMarkerHaveNoState() {
        assertThat(ReviewStateMarker.decode(null, mapper)).isEmpty();
        assertThat(ReviewStateMarker.decode("a plain review", mapper)).isEmpty();
        assertThat(ReviewStateMarker.decode("<!-- rule-review-state:v1:bm90IGd6aXA= -->", mapper)).isEmpty();
    }

    @Test
    void inlineCommentsCarryTheirFingerprint() {
        String comment = "**[ERROR · Quality] no-system-out**" + ReviewStateMarker.fingerprintMarker("0123456789abcdef");

        assertThat(ReviewStateMarker.fingerprintIn(comment)).isEqualTo("0123456789abcdef");
        assertThat(ReviewStateMarker.fingerprintIn("no marker")).isNull();
    }
}
