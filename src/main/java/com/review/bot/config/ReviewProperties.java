package com.review.bot.config;

import com.review.bot.rule.Severity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Bound from the "review" section of application.yml.
 *
 * review:
 *   github-token: ${GITHUB_TOKEN}
 *   webhook-secret: ${GITHUB_WEBHOOK_SECRET}
 *   rules:
 *     max-method-length: { enabled: true, severity: ERROR, params: { max-lines: 40 } }
 */
@ConfigurationProperties(prefix = "review")
public record ReviewProperties(
        String githubToken,
        String webhookSecret,
        String statusContext,
        List<String> excludePaths,
        Map<String, RuleConfig> rules) {

    public record RuleConfig(Boolean enabled, Severity severity, Map<String, String> params) {}

    public ReviewProperties {
        statusContext = statusContext == null ? "rule-review" : statusContext;
        excludePaths = excludePaths == null ? List.of() : excludePaths;
        rules = rules == null ? Map.of() : rules;
    }

    public boolean isEnabled(String ruleId) {
        RuleConfig c = rules.get(ruleId);
        return c == null || c.enabled() == null || c.enabled();
    }

    public Severity severityFor(String ruleId, Severity fallback) {
        RuleConfig c = rules.get(ruleId);
        return c != null && c.severity() != null ? c.severity() : fallback;
    }

    public int ruleParam(String ruleId, String key, int fallback) {
        RuleConfig c = rules.get(ruleId);
        if (c == null || c.params() == null || !c.params().containsKey(key)) return fallback;
        return Integer.parseInt(c.params().get(key));
    }

    public boolean isExcluded(String path) {
        return excludePaths.stream().anyMatch(prefix -> path.startsWith(prefix));
    }
}
