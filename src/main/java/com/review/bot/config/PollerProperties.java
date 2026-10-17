package com.review.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Bound from "review.poller" in application.yml.
 *
 * review:
 *   poller:
 *     enabled: true
 *     repos: owner/repo1,owner/repo2
 *     cron: "0 0/5 * * * *"        # every 5 minutes (the default)
 *     webhook-url: http://localhost:8080/webhook
 *
 * The cron value itself is read directly by @Scheduled (see PullRequestPoller);
 * it is listed here so it shows up in one place.
 */
@ConfigurationProperties(prefix = "review.poller")
public record PollerProperties(
        boolean enabled,
        List<String> repos,
        String cron,
        String webhookUrl) {

    public PollerProperties {
        repos = repos == null ? List.of()
                : repos.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        cron = cron == null ? "0 */5 * * * *" : cron;
        webhookUrl = webhookUrl == null ? "http://localhost:8080/webhook" : webhookUrl;
    }
}
