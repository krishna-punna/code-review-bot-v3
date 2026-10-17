package com.review.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.review.bot.cli.ReviewCli;
import com.review.bot.rejudge.Rejudge;
import com.review.bot.rejudge.RejudgeReport;
import com.review.bot.rejudge.RejudgeRequest;
import com.review.bot.rejudge.ReviewState;
import com.review.bot.review.ReviewRequest;
import com.review.bot.verdict.Decision;
import com.review.bot.verdict.MustFixItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC6 end to end: the synthetic PR in samples-rejudge/ is submitted to the running server three
 * times, each time with the state of the previous review, and every round must report what
 * samples-rejudge/expected.properties says.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RejudgeSamplesTest {

    private static final Path SAMPLE = Path.of("samples-rejudge");

    @Autowired
    private TestRestTemplate http;

    @Test
    void blockedChange_isFixedInTwoRounds_andEachRoundIsRejudgedAgainstTheLast() throws IOException {
        Properties expected = expected();

        RejudgeReport round1 = submit(1, null);
        assertThat(round1.rejudge()).isNull();
        assertThat(round1.report().decision()).isEqualTo(Decision.valueOf(expected.getProperty("round1.decision")));
        assertThat(round1.report().findings()).hasSize(Integer.parseInt(expected.getProperty("round1.findings")));

        RejudgeReport round2 = submit(2, round1.state());
        check(round2, "round2", expected);
        assertThat(round2.summaryMarkdown()).contains("Verdict: ⛔ BLOCK → ⚠️ REQUEST_CHANGES");

        RejudgeReport round3 = submit(3, round2.state());
        check(round3, "round3", expected);
        assertThat(round3.report().gate()).isEqualTo("PASS");
    }

    @Test
    void stillOpenAndNewFindings_makeUpTheUpdatedMustFixList() throws IOException {
        RejudgeReport round2 = submit(2, submit(1, null).state());

        List<String> open = new ArrayList<>();
        round2.rejudge().unresolved().forEach(f -> open.add(f.location()));
        round2.rejudge().newFindings().forEach(f -> open.add(f.location()));
        assertThat(round2.report().mustFix()).extracting(MustFixItem::location).containsExactlyInAnyOrderElementsOf(open);
    }

    @Test
    void firstSubmission_answersLikeAPlainReview() throws IOException {
        ReviewRequest change = round(1);

        RejudgeReport rejudge = http.postForObject("/api/reviews/rejudge", new RejudgeRequest(change, null), RejudgeReport.class);
        JsonNode plain = http.postForObject("/api/reviews", change, JsonNode.class);

        assertThat(rejudge.report().decision().name()).isEqualTo(plain.path("decision").asText());
        assertThat(rejudge.report().summaryMarkdown()).isEqualTo(plain.path("summaryMarkdown").asText());
        assertThat(rejudge.summaryMarkdown()).isEqualTo(plain.path("summaryMarkdown").asText());
    }

    @Test
    void requestWithoutTheChange_isRejectedWithAReason() {
        ResponseEntity<JsonNode> response = http.postForEntity("/api/reviews/rejudge", new RejudgeRequest(null, null), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().path("error").asText()).contains("\"change\"");
    }

    private void check(RejudgeReport report, String round, Properties expected) {
        Rejudge r = report.rejudge();
        assertThat(report.report().decision()).isEqualTo(Decision.valueOf(expected.getProperty(round + ".decision")));
        assertThat(r.resolved()).hasSize(Integer.parseInt(expected.getProperty(round + ".resolved")));
        assertThat(r.unresolved()).hasSize(Integer.parseInt(expected.getProperty(round + ".unresolved")));
        assertThat(r.newFindings()).hasSize(Integer.parseInt(expected.getProperty(round + ".new")));
        assertThat(r.reevaluated()).containsExactlyElementsOf(list(expected.getProperty(round + ".reevaluated")));
        assertThat(r.carriedOver()).containsExactlyElementsOf(list(expected.getProperty(round + ".carriedOver")));
    }

    private RejudgeReport submit(int round, ReviewState previous) throws IOException {
        ResponseEntity<RejudgeReport> response = http.postForEntity("/api/reviews/rejudge",
                new RejudgeRequest(round(round), previous), RejudgeReport.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return response.getBody();
    }

    private static ReviewRequest round(int round) throws IOException {
        return ReviewCli.readRequest(null, SAMPLE.resolve("round" + round), expected().getProperty("title"));
    }

    private static Properties expected() throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(SAMPLE.resolve("expected.properties"))) {
            p.load(in);
        }
        return p;
    }

    private static List<String> list(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::strip).toList();
    }
}
