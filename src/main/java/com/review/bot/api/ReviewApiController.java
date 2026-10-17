package com.review.bot.api;

import com.review.bot.review.ReviewReport;
import com.review.bot.review.ReviewRequest;
import com.review.bot.review.ReviewResult;
import com.review.bot.review.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Reviews a change sent over HTTP, no GitHub needed: a unified diff plus the touched files'
 * content, or just a set of files. Responds with the full report (verdict, must-fix, findings).
 * It only reads what is posted; it never calls GitHub.
 */
@RestController
@ConditionalOnWebApplication
public class ReviewApiController {

    private static final Logger log = LoggerFactory.getLogger(ReviewApiController.class);

    // Generous for any real PR; stops one request from tying up the server.
    static final int MAX_FILES = 1_000;
    static final long MAX_CHARS = 10_000_000;

    private final ReviewService reviews;

    public ReviewApiController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/api/reviews")
    public ReviewReport review(@RequestBody ReviewRequest request) {
        long chars = (request.diff() == null ? 0 : request.diff().length())
                + request.files().values().stream().mapToLong(c -> c == null ? 0 : c.length()).sum();
        if (request.files().size() > MAX_FILES || chars > MAX_CHARS) {
            throw new IllegalArgumentException("Request too large: at most " + MAX_FILES + " files and "
                    + MAX_CHARS + " characters.");
        }

        ReviewResult result = reviews.review(request.toChangeSet());
        log.info("API review of {} file(s): {}", result.filesReviewed(), result.verdict().reason());
        return ReviewReport.of(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
