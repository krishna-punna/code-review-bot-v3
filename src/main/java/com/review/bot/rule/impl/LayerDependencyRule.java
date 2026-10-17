package com.review.bot.rule.impl;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.review.bot.rule.Category;
import com.review.bot.rule.FileContext;
import com.review.bot.rule.Finding;
import com.review.bot.rule.Rule;
import com.review.bot.rule.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Architecture rule: the web layer must not depend on the persistence layer directly.
 * A controller (annotated @RestController/@Controller, or living in a "controller"/"web"
 * package) that imports a repository (a "repository"/"dao" package, or a type named
 * *Repository / *Dao) is flagged. Controllers should go through a service.
 */
@Component
public class LayerDependencyRule implements Rule {

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("RestController", "Controller");

    @Override public String id() { return "layer-dependency"; }
    @Override public Category category() { return Category.ARCHITECTURE; }
    @Override public Severity defaultSeverity() { return Severity.ERROR; }

    @Override
    public String remediation() {
        return "Remove the repository dependency from the controller. Add or reuse a @Service that "
                + "wraps the repository call, inject that service into the controller instead, and keep "
                + "transaction and persistence logic in the service layer.";
    }

    @Override
    public boolean appliesTo(FileContext file) {
        return file.isJava() && !file.isTest();
    }

    @Override
    public List<Finding> check(FileContext file, Severity severity) {
        return file.ast()
                .filter(this::isController)
                .map(cu -> cu.getImports().stream()
                        .filter(this::isPersistenceImport)
                        .map(imp -> {
                            String imported = imp.getNameAsString();
                            String simple = imported.substring(imported.lastIndexOf('.') + 1);
                            return finding(file, imp.getBegin().map(p -> p.line).orElse(1), severity,
                                    "Controller depends directly on persistence type `" + imported
                                            + "`, bypassing the service layer.",
                                    "Remove the import of `" + simple + "` from this controller. Move the "
                                            + "calls that use it into a @Service method, and inject that "
                                            + "service into the controller instead.");
                        })
                        .toList())
                .orElse(List.of());
    }

    private boolean isController(CompilationUnit cu) {
        boolean annotated = cu.getTypes().stream()
                .flatMap(t -> t.getAnnotations().stream())
                .anyMatch(a -> CONTROLLER_ANNOTATIONS.contains(a.getName().getIdentifier()));
        boolean inWebPackage = cu.getPackageDeclaration()
                .map(p -> hasSegment(p.getNameAsString(), "controller", "controllers", "web"))
                .orElse(false);
        return annotated || inWebPackage;
    }

    private boolean isPersistenceImport(ImportDeclaration imp) {
        if (imp.isStatic()) return false;
        String name = imp.getNameAsString();
        String simple = name.substring(name.lastIndexOf('.') + 1);
        if (name.startsWith("org.springframework.")) return false;   // framework types, e.g. @Repository itself
        return hasSegment(name, "repository", "repositories", "dao")
                || simple.endsWith("Repository") || simple.endsWith("Dao");
    }

    private static boolean hasSegment(String dottedName, String... segments) {
        for (String part : dottedName.split("\\.")) {
            for (String s : segments) {
                if (part.equals(s)) return true;
            }
        }
        return false;
    }
}
