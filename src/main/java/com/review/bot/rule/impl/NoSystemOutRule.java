package com.review.bot.rule.impl;

import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.review.bot.rule.Category;
import com.review.bot.rule.FileContext;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Rule;
import com.review.bot.rule.Severity;
import org.springframework.stereotype.Component;

import java.util.List;

/** Flags System.out / System.err / printStackTrace() in production code. Use a logger. */
@Component
public class NoSystemOutRule implements Rule {

    @Override public String id() { return "no-system-out"; }
    @Override public Category category() { return Category.QUALITY; }
    @Override public Severity defaultSeverity() { return Severity.ERROR; }

    @Override
    public String remediation() {
        return "Replace console output with an SLF4J logger: declare "
                + "`private static final Logger log = LoggerFactory.getLogger(<ThisClass>.class);` "
                + "and call `log.info(...)` / `log.error(\"...\", e)` instead.";
    }

    @Override
    public boolean appliesTo(FileContext file) {
        return file.isJava() && !file.isTest();
    }

    @Override
    public List<Finding> check(FileContext file, Severity severity) {
        return file.ast().map(cu -> cu.findAll(MethodCallExpr.class).stream()
                .filter(this::isConsolePrint)
                .map(call -> toFinding(file, call, severity))
                .toList()
        ).orElse(List.of());
    }

    private Finding toFinding(FileContext file, MethodCallExpr call, Severity severity) {
        int line = call.getBegin().map(p -> p.line).orElse(1);
        if (call.getNameAsString().equals("printStackTrace")) {
            return finding(file, line, severity,
                    "`printStackTrace()` writes to stderr, bypassing log levels, formatting and log shipping.",
                    "Remove `printStackTrace()` and log the exception with its stack trace: "
                            + "`log.error(\"<what failed>\", e);`.");
        }
        return finding(file, line, severity,
                "Console output via `" + call.getScope().map(Object::toString).orElse("System.out")
                        + "." + call.getNameAsString() + "` in production code.");
    }

    private boolean isConsolePrint(MethodCallExpr call) {
        if (call.getNameAsString().equals("printStackTrace") && call.getArguments().isEmpty()) {
            return true;
        }
        return call.getScope()
                .filter(FieldAccessExpr.class::isInstance)
                .map(FieldAccessExpr.class::cast)
                .map(f -> f.getScope().toString().equals("System")
                        && (f.getNameAsString().equals("out") || f.getNameAsString().equals("err")))
                .orElse(false);
    }
}
