package com.review.bot.rule;

import com.review.bot.config.ReviewProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/** Runs every enabled rule against a file and keeps only findings on changed lines. */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    /** Most severe first, then by position, so the worst problems are read first. */
    public static final Comparator<Finding> ORDER =
            Comparator.comparing(Finding::severity).reversed()
                    .thenComparing(Finding::path)
                    .thenComparingInt(Finding::line)
                    .thenComparing(Finding::ruleId);

    private final List<Rule> rules;
    private final ReviewProperties props;

    public RuleEngine(List<Rule> rules, ReviewProperties props) {
        this.rules = rules.stream().sorted(Comparator.comparing(Rule::id)).toList();
        this.props = props;
        this.rules.forEach(r -> log.info("Rule loaded: {} [{} / default {}]{}",
                r.id(), r.category(), r.defaultSeverity(), props.isEnabled(r.id()) ? "" : " (disabled)"));
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<Finding> review(FileContext file) {
        if (props.isExcluded(file.path())) return List.of();

        return rules.stream()
                .filter(r -> props.isEnabled(r.id()))
                .filter(r -> r.appliesTo(file))
                .flatMap(r -> runSafely(r, file).stream())
                .filter(f -> file.changedLines().contains(f.line()))
                .sorted(ORDER)
                .toList();
    }

    private List<Finding> runSafely(Rule rule, FileContext file) {
        try {
            return rule.check(file, props.severityFor(rule.id(), rule.defaultSeverity()));
        } catch (RuntimeException e) {
            // A buggy rule must never take down the whole review.
            log.error("Rule {} failed on {}", rule.id(), file.path(), e);
            return List.of();
        }
    }
}
