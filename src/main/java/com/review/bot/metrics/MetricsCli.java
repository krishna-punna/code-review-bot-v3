package com.review.bot.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.review.bot.ReviewBotApplication;
import com.review.bot.rejudge.RejudgeService;
import com.review.bot.review.ReviewService;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AC8 commands: measure the bot's time to decision and compare it with the manual baseline.
 *
 *   benchmark  times the bot on samples/ and samples-rejudge/ and writes the AC8 report
 *   history    reads a GitHub repository's review history as a baseline
 *
 * Exit code: 0 done, 2 bad arguments or input.
 */
public final class MetricsCli {

    static final String USAGE = """
            usage: java -jar code-review-bot-v3.jar benchmark [--runs N] [--baseline CSV] [--history JSON] [--out DIR]
                   java -jar code-review-bot-v3.jar history --repo OWNER/NAME [--limit N] [--context NAME] [--out DIR]

              benchmark  time the bot's verdict on samples/ and samples-rejudge/ and write the AC8 report
                         (ac8-report.md, benchmark.json; --out defaults to target/ac8)
                --runs N          timed runs per review (default 20, after 3 warm-up runs)
                --baseline CSV    manual review timings, see docs/ac8/manual-baseline.csv
                --history JSON    history.json written by the history command
              history    read a GitHub repository's merged PRs: time to first human review, rounds of requested
                         changes, time to merge, and the bot's time to its first verdict (history.json, pr-history.csv)
                --limit N         merged PRs to read (default 30; about 3 API requests each)
                --context NAME    the bot's commit status context (default rule-review)
                         Uses GITHUB_TOKEN from the environment; without it only public repositories, 60 requests/hour.
            exit code: 0 done, 2 bad arguments or input""";

    private static final Set<String> COMMANDS = Set.of("benchmark", "history");
    private static final Map<String, Set<String>> OPTIONS = Map.of(
            "benchmark", Set.of("--runs", "--baseline", "--history", "--out", "--samples", "--rejudge"),
            "history", Set.of("--repo", "--limit", "--context", "--out"));

    private MetricsCli() {}

    public static boolean handles(String[] args) {
        return args.length > 0 && COMMANDS.contains(args[0]);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (List.of(args).contains("--help")) {
            out.println(USAGE);
            return 0;
        }
        try {
            Map<String, String> opts = parse(args);
            return args[0].equals("benchmark") ? benchmark(opts, out) : history(opts, out, err);
        } catch (IllegalArgumentException | IOException e) {
            err.println("error: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }
    }

    private static int benchmark(Map<String, String> opts, PrintStream out) throws IOException {
        int runs = number(opts, "--runs", 20);
        Path samples = Path.of(opts.getOrDefault("--samples", "samples"));
        Path rejudge = Path.of(opts.getOrDefault("--rejudge", "samples-rejudge"));
        if (!Files.isDirectory(samples)) {
            throw new IllegalArgumentException("no samples folder at " + samples.toAbsolutePath() + ": run this from the project folder");
        }
        List<ManualBaseline.Row> baseline = opts.containsKey("--baseline")
                ? ManualBaseline.read(Path.of(opts.get("--baseline"))) : List.of();
        ObjectMapper mapper = new ObjectMapper();
        PrHistory.Summary history = opts.containsKey("--history")
                ? mapper.readValue(Path.of(opts.get("--history")).toFile(), PrHistory.Summary.class) : null;

        // Same beans and application.yml as the server, minus the web server and the poller.
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ReviewBotApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run("--review.poller.enabled=false", "--logging.level.root=WARN")) {

            Benchmark.Result result = new Benchmark(ctx.getBean(ReviewService.class), ctx.getBean(RejudgeService.class),
                    ctx.getBean(ObjectMapper.class)).run(samples, Files.isDirectory(rejudge) ? rejudge : null, runs);
            String report = Ac8Report.markdown(result, baseline, history, LocalDate.now().toString(), environment());

            Path outDir = Path.of(opts.getOrDefault("--out", "target/ac8"));
            Files.createDirectories(outDir);
            ObjectWriter json = mapper.writerWithDefaultPrettyPrinter();
            Files.writeString(outDir.resolve("benchmark.json"), json.writeValueAsString(result) + "\n");
            Files.writeString(outDir.resolve("ac8-report.md"), report);
            out.print(report);
            out.println("Written: " + outDir.resolve("ac8-report.md") + ", " + outDir.resolve("benchmark.json"));
            return 0;
        }
    }

    private static int history(Map<String, String> opts, PrintStream out, PrintStream err) throws IOException {
        String repo = opts.get("--repo");
        if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) throw new IllegalArgumentException("give --repo OWNER/NAME");
        int limit = number(opts, "--limit", 30);
        String context = opts.getOrDefault("--context", "rule-review");

        String token = System.getenv("GITHUB_TOKEN");
        GitHub github;
        if (token == null || token.isBlank()) {
            err.println("note: GITHUB_TOKEN is not set; reading anonymously (public repositories only, 60 requests/hour)");
            github = GitHub.connectAnonymously();
        } else {
            github = new GitHubBuilder().withOAuthToken(token).build();
        }

        PrHistory.Summary summary = PrHistory.analyze(repo, PrHistory.fetch(github, repo, limit), context);

        Path outDir = Path.of(opts.getOrDefault("--out", "target/ac8"));
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("history.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary) + "\n");
        StringBuilder csv = new StringBuilder("pr,author,hours_to_first_human_review,changes_requested,hours_to_merge,seconds_to_bot_verdict\n");
        for (PrHistory.PrRecord r : summary.records()) {
            csv.append(r.number()).append(',').append(r.author()).append(',').append(blank(r.hoursToFirstHumanReview()))
                    .append(',').append(r.changesRequested()).append(',').append(blank(r.hoursToMerge()))
                    .append(',').append(blank(r.secondsToBotDecision())).append('\n');
        }
        Files.writeString(outDir.resolve("pr-history.csv"), csv.toString());
        out.print(Ac8Report.historySection(summary));
        out.println("Written: " + outDir.resolve("history.json") + ", " + outDir.resolve("pr-history.csv")
                + ". Add it to the report: benchmark --history " + outDir.resolve("history.json"));
        return 0;
    }

    private static Map<String, String> parse(String[] args) {
        Set<String> allowed = OPTIONS.get(args[0]);
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!allowed.contains(args[i])) throw new IllegalArgumentException("unknown option " + args[i] + " for " + args[0]);
            if (i + 1 >= args.length) throw new IllegalArgumentException(args[i] + " needs a value");
            opts.put(args[i], args[++i]);
        }
        return opts;
    }

    private static int number(Map<String, String> opts, String key, int fallback) {
        if (!opts.containsKey(key)) return fallback;
        try {
            int n = Integer.parseInt(opts.get(key));
            if (n < 1) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number of at least 1");
        }
    }

    private static String environment() {
        return "Java " + Runtime.version().feature() + ", " + System.getProperty("os.name") + ", "
                + Runtime.getRuntime().availableProcessors() + " CPUs";
    }

    private static String blank(Double value) {
        return value == null ? "" : String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
