package com.review.bot;

import com.review.bot.cli.ReviewCli;
import com.review.bot.review.ReviewReport;
import com.review.bot.review.ReviewReport.FindingView;
import com.review.bot.review.ReviewRequest;
import com.review.bot.verdict.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC1: each sample PR diff in samples/ is submitted to the running server over HTTP and must get
 * the verdict and findings its expected.properties names, with no manual step in between.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SamplesEndToEndTest {

    @Autowired
    private TestRestTemplate http;

    static List<Path> samples() throws IOException {
        try (var dirs = Files.list(Path.of("samples"))) {
            return dirs.filter(Files::isDirectory).sorted().toList();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void sampleDiff_getsItsExpectedVerdictAndFindings(Path sample) throws IOException {
        Properties expected = expected(sample);
        Decision decision = Decision.valueOf(expected.getProperty("decision"));
        ReviewRequest request = ReviewCli.readRequest(sample.resolve("pr.diff"), sample.resolve("head"),
                expected.getProperty("title"));

        ResponseEntity<ReviewReport> response = http.postForEntity("/api/reviews", request, ReviewReport.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ReviewReport report = response.getBody();
        assertThat(report.title()).isEqualTo(expected.getProperty("title"));
        assertThat(report.decision()).isEqualTo(decision);
        assertThat(report.gate()).isEqualTo(decision == Decision.APPROVE ? "PASS" : "FAIL");
        assertThat(report.mustFix()).hasSize(Integer.parseInt(expected.getProperty("mustFix")));
        assertThat(report.findings()).extracting(FindingView::ruleId).containsExactlyElementsOf(ruleIds(expected));
        assertThat(report.findings()).allSatisfy(f -> {
            assertThat(f.location()).isEqualTo(f.path() + ":" + f.line());
            assertThat(f.description()).isNotBlank();
            assertThat(f.remediation()).isNotBlank();
        });
        assertThat(report.summaryMarkdown()).contains("Verdict: **" + decision + "**");
    }

    @Test
    void theSamplesCoverEveryVerdict() throws IOException {
        Set<Decision> covered = EnumSet.noneOf(Decision.class);
        for (Path sample : samples()) covered.add(Decision.valueOf(expected(sample).getProperty("decision")));
        assertThat(covered).containsExactlyInAnyOrder(Decision.values());
    }

    @Test
    void sameSampleTwice_givesTheSameReport() throws IOException {
        Path sample = Path.of("samples/03-errors-block");
        ReviewRequest request = ReviewCli.readRequest(sample.resolve("pr.diff"), sample.resolve("head"), "t");

        String first = http.postForObject("/api/reviews", request, String.class);
        String second = http.postForObject("/api/reviews", request, String.class);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void inputThatIsNotADiff_isRejectedWithAReason() {
        ReviewRequest bad = new ReviewRequest("x", "please review my code", Map.of());

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> response = http.postForEntity("/api/reviews", bad, Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("error")).asString().contains("No file changes found");
    }

    private static Properties expected(Path sample) throws IOException {
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(sample.resolve("expected.properties"))) {
            p.load(in);
        }
        return p;
    }

    private static List<String> ruleIds(Properties expected) {
        return Arrays.stream(expected.getProperty("findings", "").split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
