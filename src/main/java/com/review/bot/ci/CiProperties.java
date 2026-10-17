package com.review.bot.ci;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CI gate settings (AC7), bound from "review.ci" in application.yml.
 *
 * review:
 *   ci:
 *     summary-on-approve: true   # also post a summary on clean PRs
 *
 * @param summaryOnApprove  post a summary review even when there is nothing to fix, so every
 *                          evaluated commit has a summary for the status check to link to
 */
@ConfigurationProperties(prefix = "review.ci")
public record CiProperties(Boolean summaryOnApprove) {

    public CiProperties {
        summaryOnApprove = summaryOnApprove == null || summaryOnApprove;
    }

    public static CiProperties defaults() {
        return new CiProperties(null);
    }
}
