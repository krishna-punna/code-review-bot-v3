package com.review.bot.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.config.ReviewProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Receives GitHub "pull_request" webhooks and reviews the PR in the background. */
@RestController
@ConditionalOnWebApplication   // not in CLI mode, which has no web server
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

    private final PullRequestReviewer reviewer;
    private final ReviewProperties props;
    private final ObjectMapper mapper;
    // Small fixed pool: reviews are I/O-bound GitHub calls, a few at a time is plenty.
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("pr-review-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    public WebhookController(PullRequestReviewer reviewer, ReviewProperties props, ObjectMapper mapper) {
        this.reviewer = reviewer;
        this.props = props;
        this.mapper = mapper;
        if (!hasSecret()) {
            log.error("No webhook secret configured (GITHUB_WEBHOOK_SECRET); every /webhook request will be rejected");
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> onEvent(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody byte[] body) throws Exception {

        if (!validSignature(body, signature)) {
            return ResponseEntity.status(401).body("bad signature");
        }
        if (!"pull_request".equals(event)) {
            return ResponseEntity.ok("ignored event " + event);
        }

        JsonNode json = mapper.readTree(body);
        String action = json.path("action").asText();
        boolean draft = json.path("pull_request").path("draft").asBoolean(false);
        if (!REVIEW_ACTIONS.contains(action) || draft) {
            return ResponseEntity.ok("ignored action " + action);
        }

        String repo = json.path("repository").path("full_name").asText();
        int number = json.path("number").asInt();

        // GitHub times out webhooks after ~10s, so acknowledge now and work in the background.
        executor.submit(() -> {
            try {
                reviewer.review(repo, number);
            } catch (Exception e) {
                log.error("Review failed for {}#{}", repo, number, e);
            }
        });
        return ResponseEntity.accepted().body("reviewing " + repo + "#" + number);
    }

    private boolean hasSecret() {
        return props.webhookSecret() != null && !props.webhookSecret().isBlank();
    }

    // Fails closed: without a secret anyone could trigger reviews on any repo the token can reach.
    private boolean validSignature(byte[] body, String header) throws Exception {
        if (!hasSecret()) return false;
        if (header == null || !header.startsWith("sha256=")) return false;

        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(header.substring("sha256=".length()));
        } catch (IllegalArgumentException e) {
            return false;                                  // not hex, or odd length
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(body);
        return MessageDigest.isEqual(expected, actual);   // constant-time compare
    }
}
