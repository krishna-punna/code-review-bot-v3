package com.review.bot.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.rejudge.RejudgeRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/** AC6 through the CLI: review --out, fix, review --previous, and so on. */
class RejudgeCliTest {

    private static final String SAMPLE = "samples-rejudge";

    @TempDir
    Path tmp;

    private ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    private int run(String... args) {
        stdout = new ByteArrayOutputStream();
        stderr = new ByteArrayOutputStream();
        return ReviewCli.run(args, new PrintStream(stdout, true, UTF_8), new PrintStream(stderr, true, UTF_8));
    }

    private JsonNode json(Path file) throws IOException {
        return new ObjectMapper().readTree(file.toFile());
    }

    @Test
    void threeRounds_blockThenRequestChangesThenApprove() throws IOException {
        Path r1 = tmp.resolve("r1"), r2 = tmp.resolve("r2"), r3 = tmp.resolve("r3");

        assertThat(run("review", "--source", SAMPLE + "/round1", "--out", r1.toString())).isEqualTo(1);
        assertThat(r1.resolve("state.json")).exists();
        assertThat(r1.resolve("rejudge.json")).doesNotExist();

        assertThat(run("review", "--source", SAMPLE + "/round2", "--previous", r1.toString(), "--out", r2.toString())).isEqualTo(1);
        JsonNode rejudge = json(r2.resolve("rejudge.json"));
        assertThat(rejudge.path("previousDecision").asText()).isEqualTo("BLOCK");
        assertThat(rejudge.path("resolved")).hasSize(3);
        assertThat(rejudge.path("unresolved")).hasSize(2);
        assertThat(rejudge.path("newFindings")).hasSize(1);
        assertThat(json(r2.resolve("result.json")).path("decision").asText()).isEqualTo("REQUEST_CHANGES");
        assertThat(stdout.toString(UTF_8)).contains("🔁 Since the previous review", "✅ Resolved (3)", "❌ Still open (2)", "🆕 New (1)");
        assertThat(Files.readString(r2.resolve("summary.md"))).isEqualTo(stdout.toString(UTF_8));

        assertThat(run("review", "--source", SAMPLE + "/round3", "--previous", r2.resolve("state.json").toString(),
                "--out", r3.toString())).isZero();
        assertThat(stdout.toString(UTF_8)).contains("Verdict: ⚠️ REQUEST_CHANGES → ✅ APPROVE", "✅ Resolved (3)");
    }

    @Test
    void jsonFormat_withPrevious_printsTheRejudgeReport() throws IOException {
        Path r1 = tmp.resolve("r1");
        run("review", "--source", SAMPLE + "/round1", "--out", r1.toString());

        run("review", "--source", SAMPLE + "/round2", "--previous", r1.toString(), "--format", "json");

        JsonNode printed = new ObjectMapper().readTree(stdout.toString(UTF_8));
        assertThat(printed.path("report").path("decision").asText()).isEqualTo("REQUEST_CHANGES");
        assertThat(printed.path("rejudge").path("carriedOver").get(0).asText()).endsWith("ReportService.java");
        assertThat(printed.path("state").path("files")).hasSize(2);
    }

    @Test
    void requestCommand_withPrevious_printsARejudgeApiBody() throws IOException {
        Path r1 = tmp.resolve("r1");
        run("review", "--source", SAMPLE + "/round1", "--out", r1.toString());

        assertThat(run("request", "--source", SAMPLE + "/round2", "--previous", r1.toString())).isZero();

        RejudgeRequest body = new ObjectMapper().readValue(stdout.toString(UTF_8), RejudgeRequest.class);
        assertThat(body.change().files()).hasSize(2);
        assertThat(body.previous().findings()).hasSize(5);
    }

    @Test
    void previousCanBeASavedApiResponse() throws IOException {
        Path r1 = tmp.resolve("r1");
        run("review", "--source", SAMPLE + "/round1", "--out", r1.toString());
        Path response = tmp.resolve("response.json");
        Files.writeString(response, "{\"report\":{},\"state\":" + Files.readString(r1.resolve("state.json")) + "}");

        assertThat(run("review", "--source", SAMPLE + "/round2", "--previous", response.toString())).isEqualTo(1);
        assertThat(stdout.toString(UTF_8)).contains("✅ Resolved (3)");
    }

    @Test
    void missingOrBrokenPrevious_exitsWith2_andSaysWhy() throws IOException {
        assertThat(run("review", "--source", SAMPLE + "/round2", "--previous", tmp.resolve("nothing").toString())).isEqualTo(2);
        assertThat(stderr.toString(UTF_8)).contains("no previous review at");

        Path broken = tmp.resolve("state.json");
        Files.writeString(broken, "not json");
        assertThat(run("review", "--source", SAMPLE + "/round2", "--previous", broken.toString())).isEqualTo(2);
        assertThat(stderr.toString(UTF_8)).contains("not a review state");
    }
}
