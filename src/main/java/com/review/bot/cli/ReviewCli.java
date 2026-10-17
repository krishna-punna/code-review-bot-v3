package com.review.bot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.review.bot.ReviewBotApplication;
import com.review.bot.review.ChangeSet;
import com.review.bot.review.ReviewReport;
import com.review.bot.review.ReviewRequest;
import com.review.bot.review.ReviewResult;
import com.review.bot.review.ReviewService;
import com.review.bot.review.UnifiedDiff;
import com.review.bot.review.UnifiedDiff.FilePatch;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Command-line entry point: reviews a change on disk, with no GitHub and no running server.
 * It runs the same {@code ReviewService} and rule config as the server.
 *
 * With --diff, findings are kept only on the lines the diff adds, and each touched file's full
 * new content is read from --source (added files can be rebuilt from the diff alone).
 * Without --diff, every file under --source is reviewed in full.
 *
 * Exit code: 0 = gate passed, 1 = gate failed, 2 = bad arguments or input.
 */
public final class ReviewCli {

    static final String USAGE = """
            usage: java -jar code-review-bot-v3.jar review  [--diff FILE] [--source DIR] [--title TEXT]
                                                            [--format markdown|json] [--out DIR]
                   java -jar code-review-bot-v3.jar request [--diff FILE] [--source DIR] [--title TEXT]

              review   review the change and print the summary (--out DIR also writes result.json, summary.md)
              request  print the change as a POST /api/reviews request body, without reviewing it

              --diff FILE    unified diff of the change, e.g. from `git diff main...my-branch`
              --source DIR   directory holding the new version of the files (e.g. the branch checkout)
            exit code: 0 gate passed, 1 gate failed, 2 bad arguments or input""";

    private static final Set<String> COMMANDS = Set.of("review", "request");
    private static final Set<String> OPTIONS = Set.of("--diff", "--source", "--title", "--format", "--out");

    private ReviewCli() {}

    public static boolean handles(String[] args) {
        return args.length > 0 && COMMANDS.contains(args[0]);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (List.of(args).contains("--help")) {
            out.println(USAGE);
            return 0;
        }
        Map<String, String> opts;
        ReviewRequest request;
        ChangeSet change;
        try {
            opts = parse(args);
            request = readRequest(path(opts, "--diff"), path(opts, "--source"), opts.get("--title"));
            change = request.toChangeSet();   // validate before starting anything
        } catch (IllegalArgumentException | IOException e) {
            err.println("error: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }

        try {
            if (args[0].equals("request")) {
                out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(request));
                return 0;
            }
            return review(change, opts, out);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 2;
        }
    }

    private static int review(ChangeSet change, Map<String, String> opts, PrintStream out) throws IOException {
        // Same beans and application.yml as the server, minus the web server and the poller.
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ReviewBotApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run("--review.poller.enabled=false", "--logging.level.root=WARN")) {

            ReviewResult result = ctx.getBean(ReviewService.class).review(change);
            ReviewReport report = ReviewReport.of(result);
            ObjectWriter json = ctx.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter();

            Path outDir = path(opts, "--out");
            if (outDir != null) {
                Files.createDirectories(outDir);
                Files.writeString(outDir.resolve("result.json"), json.writeValueAsString(report) + "\n");
                Files.writeString(outDir.resolve("summary.md"), report.summaryMarkdown());
            }
            out.print("json".equals(opts.get("--format")) ? json.writeValueAsString(report) + "\n" : report.summaryMarkdown());
            return result.gatePassed() ? 0 : 1;
        }
    }

    /** Builds the request the CLI reviews: the diff text plus the content of every file it needs. */
    public static ReviewRequest readRequest(Path diff, Path source, String title) throws IOException {
        if (diff == null && source == null) throw new IllegalArgumentException("give --diff, --source, or both");
        Path root = source == null ? null : source.toAbsolutePath().normalize();
        if (root != null && !Files.isDirectory(root)) throw new IllegalArgumentException("not a directory: " + source);

        String diffText = diff == null ? null : Files.readString(diff);
        Map<String, String> files = new TreeMap<>();
        if (diffText != null) {
            for (FilePatch patch : UnifiedDiff.parse(diffText)) {
                if (patch.isDeleted() || root == null) continue;
                Path file = resolve(root, patch.path());
                if (Files.isRegularFile(file)) files.put(patch.path(), Files.readString(file));
            }
        } else {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (rel.startsWith(".") || rel.contains("/.")) continue;   // .git and other hidden files
                    try {
                        files.put(rel, Files.readString(file));
                    } catch (MalformedInputException e) {
                        // not UTF-8 text (an image, a jar): no rule can read it
                    }
                }
            }
        }
        String name = title != null ? title : String.valueOf(diff != null ? diff : source);
        return new ReviewRequest(name, diffText, files);
    }

    /** A path from the diff, resolved under --source. A diff can't make the CLI read outside it. */
    private static Path resolve(Path root, String path) {
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("path in the diff leaves --source: " + path);
        return file;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!OPTIONS.contains(args[i])) throw new IllegalArgumentException("unknown option " + args[i]);
            if (i + 1 >= args.length) throw new IllegalArgumentException(args[i] + " needs a value");
            opts.put(args[i], args[++i]);
        }
        String format = opts.getOrDefault("--format", "markdown");
        if (!format.equals("markdown") && !format.equals("json")) {
            throw new IllegalArgumentException("--format must be markdown or json");
        }
        return opts;
    }

    private static Path path(Map<String, String> opts, String key) {
        return opts.containsKey(key) ? Path.of(opts.get(key)) : null;
    }
}
