package com.review.bot.metrics;

import org.kohsuke.github.GHCommitStatus;
import org.kohsuke.github.GHDirection;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHPullRequestQueryBuilder;
import org.kohsuke.github.GHPullRequestReview;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Review history of a GitHub repository, as a baseline (AC8): for merged PRs, how long they waited
 * for their first human review and how many rounds of requested changes they went through; for
 * PRs the bot reviewed, how long it took to its first verdict. Both are measured from the moment
 * the PR was opened.
 */
public final class PrHistory {

    /** What the analysis needs from one PR. */
    public record PrData(int number, String author, Instant created, Instant merged, List<ReviewData> reviews,
                         List<StatusData> firstCommitStatuses) {}

    public record ReviewData(String login, String state, Instant submitted, String body) {}

    public record StatusData(String context, String state, Instant created) {}

    public record PrRecord(int number, String author, Double hoursToFirstHumanReview, int changesRequested,
                           Double hoursToMerge, Double secondsToBotDecision) {}

    public record Summary(String repository, int prs, Stats hoursToFirstHumanReview, Stats changesRequested,
                          Stats hoursToMerge, Stats secondsToBotDecision, List<PrRecord> records) {}

    private PrHistory() {}

    public static Summary analyze(String repository, List<PrData> prs, String statusContext) {
        List<PrRecord> records = prs.stream().map(pr -> record(pr, statusContext)).toList();
        return new Summary(repository, records.size(),
                Stats.of(values(records.stream().map(PrRecord::hoursToFirstHumanReview).toList())),
                Stats.of(records.stream().map(r -> (double) r.changesRequested()).toList()),
                Stats.of(values(records.stream().map(PrRecord::hoursToMerge).toList())),
                Stats.of(values(records.stream().map(PrRecord::secondsToBotDecision).toList())),
                records);
    }

    private static PrRecord record(PrData pr, String statusContext) {
        Optional<Instant> firstHuman = pr.reviews().stream()
                .filter(r -> !r.login().equals(pr.author()) && !isBotReview(r))
                .filter(r -> r.submitted() != null && !"PENDING".equals(r.state()))
                .map(ReviewData::submitted)
                .min(Comparator.naturalOrder());
        int changesRequested = (int) pr.reviews().stream()
                .filter(r -> !isBotReview(r) && "CHANGES_REQUESTED".equals(r.state()))
                .count();
        Optional<Instant> botVerdict = pr.firstCommitStatuses().stream()
                .filter(s -> statusContext.equals(s.context()))
                .filter(s -> "SUCCESS".equals(s.state()) || "FAILURE".equals(s.state()))
                .map(StatusData::created)
                .min(Comparator.naturalOrder());
        return new PrRecord(pr.number(), pr.author(),
                firstHuman.map(t -> hours(pr.created(), t)).orElse(null),
                changesRequested,
                pr.merged() == null ? null : hours(pr.created(), pr.merged()),
                botVerdict.map(t -> Math.max(0, Duration.between(pr.created(), t).toMillis() / 1000.0)).orElse(null));
    }

    /** The bot's reviews end with its policy footer; GitHub Apps' logins end in [bot]. Neither is a human review. */
    static boolean isBotReview(ReviewData r) {
        return r.login().endsWith("[bot]") || (r.body() != null && r.body().contains("<sub>Policy: "));
    }

    /**
     * Reads the most recently created merged PRs of a repository. Costs about three API calls per PR
     * (reviews, commits, statuses of the first commit).
     */
    public static List<PrData> fetch(GitHub github, String repository, int limit) throws IOException {
        GHRepository repo = github.getRepository(repository);
        List<PrData> prs = new ArrayList<>();
        for (GHPullRequest pr : repo.queryPullRequests().state(GHIssueState.CLOSED)
                .sort(GHPullRequestQueryBuilder.Sort.CREATED).direction(GHDirection.DESC)
                .list().withPageSize(Math.min(Math.max(limit, 1), 100))) {
            if (prs.size() >= limit) break;
            if (pr.getMergedAt() == null) continue;   // closed without merging: no merge decision to time

            List<ReviewData> reviews = new ArrayList<>();
            for (GHPullRequestReview r : pr.listReviews().toList()) {
                reviews.add(new ReviewData(r.getUser() == null ? "" : r.getUser().getLogin(),
                        r.getState() == null ? "" : r.getState().name(),
                        r.getSubmittedAt() == null ? null : r.getSubmittedAt().toInstant(), r.getBody()));
            }
            List<StatusData> statuses = new ArrayList<>();
            List<GHPullRequestCommitDetail> commits = pr.listCommits().toList();   // oldest first
            if (!commits.isEmpty()) {
                for (GHCommitStatus s : repo.listCommitStatuses(commits.get(0).getSha()).toList()) {
                    statuses.add(new StatusData(s.getContext(), s.getState().name(), s.getCreatedAt().toInstant()));
                }
            }
            prs.add(new PrData(pr.getNumber(), pr.getUser().getLogin(), pr.getCreatedAt().toInstant(),
                    pr.getMergedAt().toInstant(), reviews, statuses));
        }
        return prs;
    }

    private static double hours(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMinutes() / 60.0);
    }

    private static List<Double> values(List<Double> maybe) {
        return maybe.stream().filter(Objects::nonNull).toList();
    }
}
