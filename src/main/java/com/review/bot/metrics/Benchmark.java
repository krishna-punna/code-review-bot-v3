package com.review.bot.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.cli.ReviewCli;
import com.review.bot.rejudge.FileInput;
import com.review.bot.rejudge.Rejudge;
import com.review.bot.rejudge.RejudgeResult;
import com.review.bot.rejudge.RejudgeService;
import com.review.bot.rejudge.ReviewState;
import com.review.bot.review.ChangeSet;
import com.review.bot.review.ReviewReport;
import com.review.bot.review.ReviewResult;
import com.review.bot.review.ReviewService;
import com.review.bot.rule.Finding;
import com.review.bot.verdict.Decision;
import com.review.bot.verdict.Verdict;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Times the bot's decision on the sample PRs (AC8), with the same ReviewService and RejudgeService
 * the server uses, in-process: the numbers are the bot's own time to a verdict, without network
 * or JVM start-up. Also checks that every run gives the same result, and how complete each
 * finding is (the clarity criteria of the report).
 */
public final class Benchmark {

    static final int WARMUP_RUNS = 3;

    /** How much of what a reviewer needs each finding carries, summed over a review. */
    public record Clarity(int findings, int withLocation, int withSeverityAndCategory, int withFix,
                          boolean explicitVerdict, boolean mustFixOnlyBlocking) {}

    public record SampleRun(String sample, Decision decision, int findings, int mustFix, Stats millis,
                            boolean deterministic, Clarity clarity) {}

    /** One submission of the fix-and-rejudge sample (AC6). */
    public record RoundRun(int round, Decision decision, int resolved, int unresolved, int newFindings,
                           int reevaluated, int files, Stats millis) {}

    /** @param roundsToApprove submissions until the rejudge sample was approved, or null */
    public record Result(int runs, List<SampleRun> samples, List<RoundRun> rounds, Integer roundsToApprove) {}

    private final ReviewService reviews;
    private final RejudgeService rejudge;
    private final ObjectMapper mapper;

    public Benchmark(ReviewService reviews, RejudgeService rejudge, ObjectMapper mapper) {
        this.reviews = reviews;
        this.rejudge = rejudge;
        this.mapper = mapper;
    }

    /**
     * @param samplesDir  one folder per sample with pr.diff and head/ (the AC1 samples)
     * @param rejudgeDir  round1/, round2/, ... of one PR (the AC6 sample); skipped if missing
     */
    public Result run(Path samplesDir, Path rejudgeDir, int runs) throws IOException {
        if (runs < 1) throw new IllegalArgumentException("runs must be at least 1");
        List<SampleRun> samples = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(samplesDir)) {
            for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve("pr.diff"))).sorted().toList()) {
                samples.add(sample(dir, runs));
            }
        }

        List<RoundRun> rounds = new ArrayList<>();
        Integer roundsToApprove = null;
        ReviewState previous = null;
        String title = title(rejudgeDir);
        for (int i = 1; rejudgeDir != null && Files.isDirectory(rejudgeDir.resolve("round" + i)); i++) {
            ChangeSet change = ReviewCli.readRequest(null, rejudgeDir.resolve("round" + i), title).toChangeSet();
            List<FileInput> inputs = change.files().stream().map(FileInput::of).toList();
            RejudgeResult result = null;
            List<Double> millis = new ArrayList<>();
            for (int run = 0; run < WARMUP_RUNS + runs; run++) {
                long start = System.nanoTime();
                RejudgeResult r = rejudge.review(title, "", null, inputs, previous);
                if (run >= WARMUP_RUNS) millis.add((System.nanoTime() - start) / 1e6);
                result = r;
            }
            Rejudge rejudged = result.rejudge();
            Decision decision = result.result().verdict().decision();
            rounds.add(new RoundRun(i, decision,
                    rejudged == null ? 0 : rejudged.resolved().size(),
                    rejudged == null ? 0 : rejudged.unresolved().size(),
                    rejudged == null ? result.result().findings().size() : rejudged.newFindings().size(),
                    rejudged == null ? inputs.size() : rejudged.reevaluated().size(),
                    inputs.size(), Stats.of(millis)));
            if (roundsToApprove == null && decision == Decision.APPROVE) roundsToApprove = i;
            previous = result.state();
        }
        return new Result(runs, samples, rounds, roundsToApprove);
    }

    private SampleRun sample(Path dir, int runs) throws IOException {
        ChangeSet change = ReviewCli.readRequest(dir.resolve("pr.diff"), dir.resolve("head"), title(dir)).toChangeSet();
        ReviewResult first = null;
        String firstJson = null;
        boolean deterministic = true;
        List<Double> millis = new ArrayList<>();
        for (int run = 0; run < WARMUP_RUNS + runs; run++) {
            long start = System.nanoTime();
            ReviewResult result = reviews.review(change);
            if (run >= WARMUP_RUNS) millis.add((System.nanoTime() - start) / 1e6);

            String json = mapper.writeValueAsString(ReviewReport.of(result));
            if (first == null) {
                first = result;
                firstJson = json;
            } else if (!json.equals(firstJson)) {
                deterministic = false;
            }
        }
        Verdict v = first.verdict();
        return new SampleRun(dir.getFileName().toString(), v.decision(), first.findings().size(), v.mustFix().size(),
                Stats.of(millis), deterministic, clarity(first));
    }

    static Clarity clarity(ReviewResult result) {
        List<Finding> f = result.findings();
        Verdict v = result.verdict();
        return new Clarity(f.size(),
                count(f, x -> !x.path().isBlank() && x.line() >= 1),
                count(f, x -> x.severity() != null && x.category() != null),
                count(f, x -> !x.remediation().isBlank() && !x.remediation().equals(x.description())),
                v.decision() != null,
                v.mustFix().size() == v.drivingFindings().size());
    }

    private static int count(List<Finding> findings, Predicate<Finding> test) {
        return (int) findings.stream().filter(test).count();
    }

    /** The sample's title from its expected.properties, or the folder name. */
    private static String title(Path dir) throws IOException {
        if (dir == null) return "";
        Path props = dir.resolve("expected.properties");
        if (!Files.isRegularFile(props)) return dir.getFileName().toString();
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(props)) {
            p.load(in);
        }
        return p.getProperty("title", dir.getFileName().toString());
    }
}
