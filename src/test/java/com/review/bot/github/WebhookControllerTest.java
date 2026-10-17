package com.review.bot.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.review.bot.config.ReviewProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class WebhookControllerTest {

    private static final byte[] BODY = """
            {"action":"opened","number":7,"pull_request":{"draft":false},"repository":{"full_name":"acme/app"}}
            """.getBytes(StandardCharsets.UTF_8);

    private final PullRequestReviewer reviewer = mock(PullRequestReviewer.class);

    private WebhookController controller(String secret) {
        return new WebhookController(reviewer,
                new ReviewProperties("t", secret, null, List.of(), Map.of()), new ObjectMapper());
    }

    private static String sign(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void validSignature_startsReview() throws Exception {
        ResponseEntity<String> r = controller("s3cret").onEvent("pull_request", sign("s3cret", BODY), BODY);
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        verify(reviewer, timeout(2000)).review("acme/app", 7);
    }

    @Test
    void noSecretConfigured_rejectsEvenUnsignedRequests() throws Exception {
        assertThat(controller(null).onEvent("pull_request", null, BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(controller(" ").onEvent("pull_request", null, BODY).getStatusCode().value()).isEqualTo(401);
        verify(reviewer, never()).review(anyString(), anyInt());
    }

    @Test
    void missingOrWrongSignature_isRejected() throws Exception {
        WebhookController c = controller("s3cret");
        assertThat(c.onEvent("pull_request", null, BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(c.onEvent("pull_request", sign("other", BODY), BODY).getStatusCode().value()).isEqualTo(401);
        verify(reviewer, never()).review(anyString(), anyInt());
    }

    @Test
    void malformedSignature_isRejectedNotAServerError() throws Exception {
        WebhookController c = controller("s3cret");
        assertThat(c.onEvent("pull_request", "sha256=not-hex", BODY).getStatusCode().value()).isEqualTo(401);
        assertThat(c.onEvent("pull_request", "sha256=abc", BODY).getStatusCode().value()).isEqualTo(401);
    }
}
