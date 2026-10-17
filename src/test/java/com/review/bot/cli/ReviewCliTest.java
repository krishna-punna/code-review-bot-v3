package com.review.bot.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.review.ReviewRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewCliTest {

    private static final String BLOCK = "samples/03-errors-block";
    private static final String CLEAN = "samples/01-clean-change-approve";

    @TempDir
    Path tmp;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    private int run(String... args) {
        return ReviewCli.run(args, new PrintStream(stdout, true, UTF_8), new PrintStream(stderr, true, UTF_8));
    }

    private String out() {
        return stdout.toString(UTF_8);
    }

    @Test
    void blockedChange_failsTheGate_andWritesTheReport() throws IOException {
        Path outDir = tmp.resolve("out");

        int code = run("review", "--diff", BLOCK + "/pr.diff", "--source", BLOCK + "/head",
                "--title", "Expose order lookup endpoint", "--out", outDir.toString());

        assertThat(code).isEqualTo(1);
        assertThat(out()).startsWith("# Expose order lookup endpoint")
                .contains("⛔ Verdict: **BLOCK**", "Must fix before merge (3)", "OrderController.java:4");
        JsonNode json = new ObjectMapper().readTree(outDir.resolve("result.json").toFile());
        assertThat(json.path("decision").asText()).isEqualTo("BLOCK");
        assertThat(json.path("gate").asText()).isEqualTo("FAIL");
        assertThat(json.path("findings")).hasSize(4);
        assertThat(Files.readString(outDir.resolve("summary.md"))).isEqualTo(out());
    }

    @Test
    void cleanChange_passesTheGate_evenThoughTheFileHasLegacyIssuesOnUntouchedLines() {
        int code = run("review", "--diff", CLEAN + "/pr.diff", "--source", CLEAN + "/head");

        assertThat(code).isZero();
        assertThat(out()).contains("✅ Verdict: **APPROVE**", "APPROVE: no findings.");
    }

    @Test
    void sourceWithoutDiff_reviewsEveryFileInFull() {
        // Same files as the clean sample, but reviewed whole: the legacy System.out lines now count.
        int code = run("review", "--source", CLEAN + "/head", "--format", "json");

        assertThat(code).isEqualTo(1);
        assertThat(out()).contains("\"decision\" : \"BLOCK\"", "LegacyReportJob.java:12", "LegacyReportJob.java:14");
    }

    @Test
    void requestCommand_printsTheApiBodyForTheSameChange() throws IOException {
        int code = run("request", "--diff", BLOCK + "/pr.diff", "--source", BLOCK + "/head", "--title", "t");

        assertThat(code).isZero();
        ReviewRequest request = new ObjectMapper().readValue(out(), ReviewRequest.class);
        assertThat(request.files()).containsOnlyKeys("src/main/java/com/acme/orders/web/OrderController.java");
        assertThat(request.toChangeSet().files()).hasSize(1);
    }

    @Test
    void modifiedFileMissingFromSource_exitsWith2_andSaysWhy() {
        int code = run("review", "--diff", CLEAN + "/pr.diff", "--source", tmp.toString());

        assertThat(code).isEqualTo(2);
        assertThat(stderr.toString(UTF_8)).contains("No content for modified file", "LegacyReportJob.java");
    }

    @Test
    void diffCannotMakeTheCliReadOutsideSource() throws IOException {
        Path diff = tmp.resolve("evil.diff");
        Files.writeString(diff, "--- a/../secret.txt\n+++ b/../secret.txt\n@@ -1 +1 @@\n-x\n+y\n");
        Files.createDirectories(tmp.resolve("src"));

        int code = run("review", "--diff", diff.toString(), "--source", tmp.resolve("src").toString());

        assertThat(code).isEqualTo(2);
        assertThat(stderr.toString(UTF_8)).contains("leaves --source");
    }

    @Test
    void unknownOption_exitsWith2_andPrintsUsage() {
        assertThat(run("review", "--bogus", "x")).isEqualTo(2);
        assertThat(stderr.toString(UTF_8)).contains("unknown option --bogus", "usage:");
    }
}
