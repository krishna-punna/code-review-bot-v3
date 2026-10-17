package com.review.bot.poller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.config.PollerProperties;
import com.review.bot.config.ReviewProperties;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls the configured repos for open PRs and sends each PR that needs a review to the
 * existing /webhook endpoint, signed exactly like GitHub would sign it.
 *
 * The webhook flow is untouched: this is just a second, automatic caller of it.
 *
 * A PR is "legitimate" (sent for review) when:
 *  - it is open and not a draft, and
 *  - its head commit has not been sent by this poller already, and
 *  - its head commit has no "rule-review" verdict status (success or failure) yet
 *    (so PRs already reviewed via the real webhook, or before a restart, are skipped).
 */
@Component
@ConditionalOnProperty(prefix = "review.poller", name = "enabled", havingValue = "true")
public class PullRequestPoller {

    private static final Logger log = LoggerFactory.getLogger(PullRequestPoller.class);

    private final GitHub github;
    private final PollerProperties poller;
    private final ReviewProperties review;
    private final ObjectMapper mapper;
    private final RestClient http = RestClient.create();

    /** "owner/repo#12" -> head SHA already dispatched. Pruned each run to open PRs only. */
    private final Map<String, String> dispatched = new ConcurrentHashMap<>();

    public PullRequestPoller(GitHub github, PollerProperties poller, ReviewProperties review, ObjectMapper mapper) {
        this.github = github;
        this.poller = poller;
        this.review = review;
        this.mapper = mapper;
        log.info("PR poller enabled for {} on cron '{}' -> {}", poller.repos(), poller.cron(), poller.webhookUrl());
    }

    @Scheduled(cron = "${review.poller.cron:0 0/5 * * * *}")
    public void poll() {
        if (review.webhookSecret() == null || review.webhookSecret().isBlank()) {
            log.warn("Poll skipped: no webhook secret configured, so /webhook would reject every request");
            return;
        }
        Map<String, String> stillOpen = new HashMap<>();
        int sent = 0;

        for (String repoName : poller.repos()) {
            try {
                sent += pollRepo(repoName, stillOpen);
            } catch (Exception e) {
                // One bad repo (typo, no access, rate limit) must not stop the others.
                log.error("Polling {} failed: {}", repoName, e.getMessage());
            }
        }

        // Forget closed/merged PRs so the map doesn't grow forever.
        dispatched.keySet().retainAll(stillOpen.keySet());
        log.info("Poll finished: {} repo(s), {} open PR(s) tracked, {} sent for review",
                poller.repos().size(), stillOpen.size(), sent);
    }

    private int pollRepo(String repoName, Map<String, String> stillOpen) throws Exception {
        GHRepository repo = github.getRepository(repoName);
        int sent = 0;

        for (GHPullRequest pr : repo.queryPullRequests().state(GHIssueState.OPEN).list()) {
            String key = repoName + "#" + pr.getNumber();
            String sha = pr.getHead().getSha();
            stillOpen.put(key, sha);

            if (pr.isDraft()) continue;
            if (sha.equals(dispatched.get(key))) continue;          // already sent this commit

            if (alreadyReviewed(repo, sha)) {                        // reviewed by webhook or before restart
                dispatched.put(key, sha);
                continue;
            }

            if (sendToWebhook(repoName, pr.getNumber(), key)) {
                dispatched.put(key, sha);
                sent++;
            }
        }
        return sent;
    }

    /**
     * True if the head commit already carries a verdict status from this bot. ERROR (a review
     * that crashed) and PENDING don't count, so after a restart those commits are sent again.
     */
    private boolean alreadyReviewed(GHRepository repo, String sha) throws Exception {
        for (GHCommitStatus s : repo.listCommitStatuses(sha)) {
            if (review.statusContext().equals(s.getContext())
                    && (s.getState() == GHCommitState.SUCCESS || s.getState() == GHCommitState.FAILURE)) {
                return true;
            }
        }
        return false;
    }

    private boolean sendToWebhook(String repoName, int number, String key) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "synchronize");
            payload.put("number", number);
            payload.put("pull_request", Map.of("draft", false));
            payload.put("repository", Map.of("full_name", repoName));
            byte[] body = mapper.writeValueAsBytes(payload);

            String response = http.post()
                    .uri(poller.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-GitHub-Event", "pull_request")
                    .header("User-Agent", "rule-review-poller")
                    .header("X-Hub-Signature-256", "sha256=" + hmacSha256Hex(review.webhookSecret(), body))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            log.info("Sent {} for review: {}", key, response);
            return true;
        } catch (Exception e) {
            // Not marked as dispatched, so it is retried on the next run.
            log.warn("Could not send {} to webhook: {}", key, e.getMessage());
            return false;
        }
    }

    private static String hmacSha256Hex(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body)) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
