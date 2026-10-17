package com.review.bot.api;

import com.review.bot.rejudge.FileInput;
import com.review.bot.rejudge.RejudgeReport;
import com.review.bot.rejudge.RejudgeRequest;
import com.review.bot.rejudge.RejudgeResult;
import com.review.bot.rejudge.RejudgeService;
import com.review.bot.review.ChangeSet;
import com.review.bot.review.ReviewRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Fix-and-rejudge over HTTP (AC6): send the updated code plus the "state" of the previous review,
 * get back the updated verdict with each earlier finding marked resolved or still open.
 * Like POST /api/reviews, it only reads what is posted and never calls GitHub.
 */
@RestController
@ConditionalOnWebApplication
public class RejudgeApiController {

    private static final Logger log = LoggerFactory.getLogger(RejudgeApiController.class);

    private final RejudgeService rejudge;

    public RejudgeApiController(RejudgeService rejudge) {
        this.rejudge = rejudge;
    }

    @PostMapping("/api/reviews/rejudge")
    public RejudgeReport rejudge(@RequestBody RejudgeRequest body) throws IOException {
        ReviewRequest request = body.change();
        if (request == null) {
            throw new IllegalArgumentException("Send the code in \"change\" (the same shape as the body of "
                    + "POST /api/reviews) and the previous review's \"state\" in \"previous\".");
        }
        long chars = (request.diff() == null ? 0 : request.diff().length())
                + request.files().values().stream().mapToLong(c -> c == null ? 0 : c.length()).sum();
        if (request.files().size() > ReviewApiController.MAX_FILES || chars > ReviewApiController.MAX_CHARS) {
            throw new IllegalArgumentException("Request too large: at most " + ReviewApiController.MAX_FILES
                    + " files and " + ReviewApiController.MAX_CHARS + " characters.");
        }

        ChangeSet change = request.toChangeSet();
        RejudgeResult result = rejudge.review(change.title(), "", null,
                change.files().stream().map(FileInput::of).toList(), body.previous());
        log.info("API re-review of {} file(s): {}", result.result().filesReviewed(), result.result().verdict().reason());
        return RejudgeReport.of(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
