package com.review.bot.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.rejudge.ReviewState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps the review state (AC6) inside the bot's own review on the PR, as an HTML comment that
 * GitHub doesn't display. Re-judging so needs no database and survives restarts. Inline comments
 * carry their finding's fingerprint the same way, so a resolved finding can be answered in place.
 */
final class ReviewStateMarker {

    private static final Pattern STATE = Pattern.compile("<!-- rule-review-state:v1:([A-Za-z0-9+/=]+) -->");
    private static final Pattern FINGERPRINT = Pattern.compile("<!-- rule-review-fp:([0-9a-f]+) -->");

    /** GitHub rejects review bodies over 65,536 characters. */
    static final int MAX_BODY = 65_000;
    private static final int MAX_STATE_BYTES = 5_000_000;

    private ReviewStateMarker() {}

    /** The marker to append to a review body of the given length, or "" if it wouldn't fit. */
    static String encode(ReviewState state, ObjectMapper mapper, int bodyLength) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(mapper.writeValueAsBytes(state));
            }
            String marker = "\n<!-- rule-review-state:v1:" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + " -->\n";
            return bodyLength + marker.length() <= MAX_BODY ? marker : "";
        } catch (IOException e) {
            return "";   // no state: the next review simply re-evaluates every file
        }
    }

    static Optional<ReviewState> decode(String body, ObjectMapper mapper) {
        if (body == null) return Optional.empty();
        Matcher m = STATE.matcher(body);
        if (!m.find()) return Optional.empty();
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(m.group(1))))) {
            byte[] json = in.readNBytes(MAX_STATE_BYTES + 1);
            if (json.length > MAX_STATE_BYTES) return Optional.empty();
            return Optional.of(mapper.readValue(json, ReviewState.class));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    static String fingerprintMarker(String fingerprint) {
        return "\n\n<!-- rule-review-fp:" + fingerprint + " -->";
    }

    static String fingerprintIn(String body) {
        if (body == null) return null;
        Matcher m = FINGERPRINT.matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
