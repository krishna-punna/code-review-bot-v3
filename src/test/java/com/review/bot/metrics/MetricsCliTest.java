package com.review.bot.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class MetricsCliTest {

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    private int run(String... args) {
        return MetricsCli.run(args, new PrintStream(stdout, true, UTF_8), new PrintStream(stderr, true, UTF_8));
    }

    @Test
    void benchmark_writesTheReportAndTheMeasurements() throws IOException {
        Path baseline = tmp.resolve("baseline.csv");
        Files.writeString(baseline, "sample,reviewer,minutes_to_decision,decision,issues_reported,issues_with_location,"
                + "issues_with_fix,review_rounds\n03-errors-block,asha,14,BLOCK,3,2,1,1\n");

        int code = run("benchmark", "--runs", "2", "--baseline", baseline.toString(), "--out", tmp.toString());

        assertThat(code).isZero();
        assertThat(stdout.toString(UTF_8)).contains("# AC8 report", "14.0 min (1 reviewer)");
        assertThat(Files.readString(tmp.resolve("ac8-report.md"))).contains("## 2. Review rounds");
        JsonNode json = new ObjectMapper().readTree(tmp.resolve("benchmark.json").toFile());
        assertThat(json.path("runs").asInt()).isEqualTo(2);
        assertThat(json.path("samples")).hasSize(3);
        assertThat(json.path("roundsToApprove").asInt()).isEqualTo(3);
    }

    @Test
    void history_needsARepository() {
        assertThat(run("history")).isEqualTo(2);
        assertThat(stderr.toString(UTF_8)).contains("give --repo OWNER/NAME");
    }

    @Test
    void badOptions_exitWith2_andPrintUsage() {
        assertThat(run("benchmark", "--runs", "zero")).isEqualTo(2);
        assertThat(stderr.toString(UTF_8)).contains("--runs must be a whole number", "usage:");
        assertThat(run("benchmark", "--repo", "a/b")).isEqualTo(2);
        assertThat(run("benchmark", "--baseline", tmp.resolve("missing.csv").toString())).isEqualTo(2);
    }
}
