package com.review.bot.rule.impl;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.review.bot.config.ReviewProperties;
import com.review.bot.rule.Category;
import com.review.bot.rule.FileContext;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Rule;
import com.review.bot.rule.Severity;
import org.springframework.stereotype.Component;

import java.util.List;

/** Flags methods longer than a configured number of lines (default 50). */
@Component
public class MaxMethodLengthRule implements Rule {

    private final ReviewProperties props;

    public MaxMethodLengthRule(ReviewProperties props) {
        this.props = props;
    }

    @Override public String id() { return "max-method-length"; }
    @Override public Category category() { return Category.QUALITY; }
    @Override public Severity defaultSeverity() { return Severity.WARNING; }

    @Override
    public String remediation() {
        return "Extract cohesive blocks of the method into well-named private methods "
                + "until it is under the limit.";
    }

    @Override
    public List<Finding> check(FileContext file, Severity severity) {
        int max = props.ruleParam(id(), "max-lines", 50);
        return file.ast().map(cu -> cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getBody().isPresent() && m.getRange().isPresent())
                .filter(m -> m.getRange().get().getLineCount() > max)
                .map(m -> {
                    int length = m.getRange().get().getLineCount();
                    return finding(file, m.getBegin().get().line, severity,
                            "Method `%s` is %d lines long (limit %d)."
                                    .formatted(m.getNameAsString(), length, max),
                            "Reduce `%s` by at least %d lines: extract cohesive blocks into well-named "
                                    .formatted(m.getNameAsString(), length - max)
                                    + "private methods (for example validation, mapping and persistence steps).");
                })
                .toList()
        ).orElse(List.of());
    }
}
