package com.review.bot.metrics;

import com.review.bot.metrics.PrHistory.PrData;
import com.review.bot.metrics.PrHistory.PrRecord;
import com.review.bot.metrics.PrHistory.ReviewData;
import com.review.bot.metrics.PrHistory.StatusData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AC8 baseline from history: human review times next to the bot's, both from the PR being opened. */
class PrHistoryTest {

    private static final Instant OPENED = Instant.parse("2026-09-01T09:00:00Z");

    private static Instant at(String time) {
        return Instant.parse("2026-09-01T" + time + "Z");
    }

    private static final String BOT_BODY = "## ⛔ Verdict: **BLOCK**\n...\n<sub>Policy: BLOCK on [BLOCK, ERROR]</sub>";

    @Test
    void humanReviewTime_ignoresTheAuthorTheBotAndPendingReviews() {
        PrData pr = new PrData(7, "dev", OPENED, at("17:00:00"), List.of(
                new ReviewData("dev", "COMMENTED", at("09:30:00"), "self note"),          // the author
                new ReviewData("dev", "COMMENTED", at("09:00:05"), BOT_BODY),             // the bot, posting as the token owner
                new ReviewData("helper[bot]", "COMMENTED", at("09:01:00"), "lint"),       // a GitHub App
                new ReviewData("asha", "PENDING", at("10:00:00"), "draft"),               // not submitted
                new ReviewData("asha", "CHANGES_REQUESTED", at("12:00:00"), "fix the logging"),
                new ReviewData("ben", "CHANGES_REQUESTED", at("14:00:00"), "and the import"),
                new ReviewData("asha", "APPROVED", at("16:00:00"), "ok")),
                List.of(new StatusData("rule-review", "PENDING", at("09:00:01")),
                        new StatusData("rule-review", "FAILURE", at("09:00:04")),
                        new StatusData("other-ci", "SUCCESS", at("09:00:02"))));

        PrRecord r = PrHistory.analyze("acme/app", List.of(pr), "rule-review").records().get(0);

        assertThat(r.hoursToFirstHumanReview()).isEqualTo(3.0);    // asha at 12:00
        assertThat(r.changesRequested()).isEqualTo(2);
        assertThat(r.hoursToMerge()).isEqualTo(8.0);
        assertThat(r.secondsToBotDecision()).isEqualTo(4.0);       // first final rule-review status
    }

    @Test
    void prsWithoutHumanReviewsOrBotVerdicts_areLeftOutOfThoseStats() {
        PrData reviewed = new PrData(1, "dev", OPENED, at("11:00:00"),
                List.of(new ReviewData("asha", "APPROVED", at("10:00:00"), "ok")), List.of());
        PrData unreviewed = new PrData(2, "dev", OPENED, at("10:30:00"), List.of(),
                List.of(new StatusData("rule-review", "SUCCESS", at("09:00:06"))));

        PrHistory.Summary s = PrHistory.analyze("acme/app", List.of(reviewed, unreviewed), "rule-review");

        assertThat(s.prs()).isEqualTo(2);
        assertThat(s.hoursToFirstHumanReview().count()).isEqualTo(1);
        assertThat(s.hoursToFirstHumanReview().median()).isEqualTo(1.0);
        assertThat(s.secondsToBotDecision().count()).isEqualTo(1);
        assertThat(s.secondsToBotDecision().median()).isEqualTo(6.0);
        assertThat(s.hoursToMerge().median()).isEqualTo(1.75);
    }

    @Test
    void botReviews_areRecognisedByThePolicyFooterOrABotLogin() {
        assertThat(PrHistory.isBotReview(new ReviewData("dev", "COMMENTED", OPENED, BOT_BODY))).isTrue();
        assertThat(PrHistory.isBotReview(new ReviewData("x[bot]", "COMMENTED", OPENED, "hi"))).isTrue();
        assertThat(PrHistory.isBotReview(new ReviewData("asha", "APPROVED", OPENED, "looks good"))).isFalse();
    }
}
