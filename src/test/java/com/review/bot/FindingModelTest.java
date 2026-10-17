package com.review.bot;

import com.review.bot.config.ReviewProperties;
import com.review.bot.rule.*;
import com.review.bot.rule.impl.LayerDependencyRule;
import com.review.bot.rule.impl.MaxMethodLengthRule;
import com.review.bot.rule.impl.NoFieldInjectionRule;
import com.review.bot.rule.impl.NoSystemOutRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 1: AC2 (severity, category, description, location) and AC4 (actionable remediation). */
class FindingModelTest {

    private static final String SRC = """
            package demo;
            class Foo {
                @Autowired private Bar bar;
                void a() {
                    System.out.println("hi");
                }
            }
            """;

    private static RuleEngine engine(Map<String, ReviewProperties.RuleConfig> config) {
        ReviewProperties props = new ReviewProperties("t", null, null, List.of(), config);
        return new RuleEngine(List.of(new NoSystemOutRule(), new NoFieldInjectionRule(),
                new MaxMethodLengthRule(props), new LayerDependencyRule()), props);
    }

    private static List<Finding> review(RuleEngine engine, String path, String src) {
        return engine.review(new FileContext(path, src, RulesTest.allLines()));
    }

    // ---- AC2 / AC4: every finding is complete ----------------------------------------------

    @Test
    void everyFindingHasSeverityCategoryDescriptionLocationAndRemediation() {
        List<Finding> findings = review(engine(Map.of()), "src/main/java/demo/Foo.java", SRC);

        assertThat(findings).isNotEmpty().allSatisfy(f -> {
            assertThat(f.severity()).isNotNull();
            assertThat(f.category()).isNotNull();
            assertThat(f.description()).isNotBlank();
            assertThat(f.remediation()).isNotBlank();
            assertThat(f.path()).isEqualTo("src/main/java/demo/Foo.java");
            assertThat(f.line()).isPositive();
            assertThat(f.location()).isEqualTo(f.path() + ":" + f.line());
            assertThat(f.fingerprint()).hasSize(16);
        });
    }

    @Test
    void everyRuleDeclaresCategoryAndRemediation() {
        assertThat(engine(Map.of()).rules()).allSatisfy(r -> {
            assertThat(r.category()).as(r.id()).isNotNull();
            assertThat(r.remediation()).as(r.id()).isNotBlank();
        });
    }

    @Test
    void categoriesAreAssignedPerRule() {
        List<Finding> findings = review(engine(Map.of()), "src/main/java/demo/Foo.java", SRC);
        assertThat(findings)
                .extracting(Finding::ruleId, Finding::category)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("no-system-out", Category.QUALITY),
                        org.assertj.core.groups.Tuple.tuple("no-field-injection", Category.ARCHITECTURE));
    }

    @Test
    void remediationIsSpecificToTheFinding() {
        Finding f = review(engine(Map.of()), "src/main/java/demo/Foo.java", SRC).stream()
                .filter(x -> x.ruleId().equals("no-field-injection")).findFirst().orElseThrow();
        assertThat(f.remediation()).contains("private final Bar bar;");
    }

    @Test
    void findingWithoutRemediationIsRejected() {
        assertThatThrownBy(() -> new Finding("r", Category.QUALITY, Severity.WARNING, "A.java", 1,
                "something is wrong", " ", "abcd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remediation");
    }

    @Test
    void markdownShowsSeverityCategoryAndFix() {
        Finding f = review(engine(Map.of()), "src/main/java/demo/Foo.java", SRC).get(0);
        assertThat(f.toMarkdown())
                .contains("ERROR", "Quality", "no-system-out", "**Fix:**", "LoggerFactory");
    }

    // ---- Severities -------------------------------------------------------------------------

    @Test
    void severityOrderIsWarningErrorBlock() {
        assertThat(Severity.BLOCK.isAtLeast(Severity.ERROR)).isTrue();
        assertThat(Severity.ERROR.isAtLeast(Severity.WARNING)).isTrue();
        assertThat(Severity.WARNING.isAtLeast(Severity.ERROR)).isFalse();
    }

    @Test
    void severityCanBeOverriddenToBlockInConfig() {
        RuleEngine e = engine(Map.of("no-system-out",
                new ReviewProperties.RuleConfig(true, Severity.BLOCK, null)));
        Finding f = review(e, "src/main/java/demo/Foo.java", SRC).get(0);
        assertThat(f.ruleId()).isEqualTo("no-system-out");
        assertThat(f.severity()).isEqualTo(Severity.BLOCK);
    }

    // ---- Fingerprint (used by fix-and-rejudge later) ----------------------------------------

    @Test
    void fingerprintSurvivesCodeMovingDown() {
        RuleEngine e = engine(Map.of());
        String shifted = SRC.replace("package demo;\n", "package demo;\n\n// new comment\n\n");

        Finding before = review(e, "src/main/java/demo/Foo.java", SRC).get(0);
        Finding after = review(e, "src/main/java/demo/Foo.java", shifted).get(0);

        assertThat(after.line()).isEqualTo(before.line() + 3);
        assertThat(after.fingerprint()).isEqualTo(before.fingerprint());
    }

    @Test
    void fingerprintChangesWhenTheOffendingLineChanges() {
        RuleEngine e = engine(Map.of());
        String edited = SRC.replace("System.out.println(\"hi\");", "System.out.println(\"bye\");");
        assertThat(review(e, "src/main/java/demo/Foo.java", edited).get(0).fingerprint())
                .isNotEqualTo(review(e, "src/main/java/demo/Foo.java", SRC).get(0).fingerprint());
    }

    // ---- New architecture rule --------------------------------------------------------------

    private static final String CONTROLLER = """
            package com.acme.web;
            import org.springframework.web.bind.annotation.RestController;
            import org.springframework.stereotype.Repository;
            import com.acme.repository.OrderRepository;
            import com.acme.service.OrderService;
            @RestController
            class OrderController {
                private final OrderRepository repo;
                OrderController(OrderRepository repo) { this.repo = repo; }
            }
            """;

    @Test
    void controllerImportingRepositoryIsFlagged() {
        List<Finding> f = review(engine(Map.of()), "src/main/java/com/acme/web/OrderController.java", CONTROLLER);
        assertThat(f).extracting(Finding::ruleId).containsExactly("layer-dependency");
        assertThat(f.get(0).line()).isEqualTo(4);
        assertThat(f.get(0).category()).isEqualTo(Category.ARCHITECTURE);
        assertThat(f.get(0).severity()).isEqualTo(Severity.ERROR);
        assertThat(f.get(0).remediation()).contains("OrderRepository", "@Service");
    }

    @Test
    void serviceImportingRepositoryIsAllowed() {
        String service = CONTROLLER
                .replace("package com.acme.web;", "package com.acme.service;")
                .replace("@RestController", "@Service")
                .replace("OrderController", "OrderServiceImpl");
        List<Finding> f = review(engine(Map.of()), "src/main/java/com/acme/service/OrderServiceImpl.java", service);
        assertThat(f).extracting(Finding::ruleId).doesNotContain("layer-dependency");
    }

    @Test
    void onlyChangedImportLinesAreReported() {
        RuleEngine e = engine(Map.of());
        List<Finding> f = e.review(new FileContext(
                "src/main/java/com/acme/web/OrderController.java", CONTROLLER, Set.of(9)));
        assertThat(f).isEmpty();
    }
}
