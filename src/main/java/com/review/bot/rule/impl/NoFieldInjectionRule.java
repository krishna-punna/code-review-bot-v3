package com.review.bot.rule.impl;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.review.bot.rule.Category;
import com.review.bot.rule.FileContext;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Rule;
import com.review.bot.rule.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Flags @Autowired / @Inject / @Resource on fields. Constructor injection is required. */
@Component
public class NoFieldInjectionRule implements Rule {

    private static final Set<String> INJECT_ANNOTATIONS = Set.of("Autowired", "Inject", "Resource");

    @Override public String id() { return "no-field-injection"; }
    @Override public Category category() { return Category.ARCHITECTURE; }
    @Override public Severity defaultSeverity() { return Severity.WARNING; }

    @Override
    public String remediation() {
        return "Remove the annotation, make the field `private final`, and add it as a constructor "
                + "parameter (or use Lombok `@RequiredArgsConstructor`).";
    }

    @Override
    public boolean appliesTo(FileContext file) {
        return file.isJava() && !file.isTest();
    }

    @Override
    public List<Finding> check(FileContext file, Severity severity) {
        return file.ast().map(cu -> cu.findAll(FieldDeclaration.class).stream()
                .filter(f -> f.getAnnotations().stream()
                        .anyMatch(a -> INJECT_ANNOTATIONS.contains(a.getName().getIdentifier())))
                .map(f -> {
                    String name = f.getVariable(0).getNameAsString();
                    String type = f.getElementType().asString();
                    return finding(file, f.getBegin().map(p -> p.line).orElse(1), severity,
                            "Field injection on `" + name + "` hides the dependency and makes the class "
                                    + "hard to unit-test without a Spring context.",
                            "Remove the injection annotation from `" + name + "`, declare it as "
                                    + "`private final " + type + " " + name + ";`, and assign it from a "
                                    + "constructor parameter.");
                })
                .toList()
        ).orElse(List.of());
    }
}
