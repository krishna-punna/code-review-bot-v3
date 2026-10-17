package com.review.bot;

import com.review.bot.config.ReviewProperties;
import com.review.bot.github.DiffParser;
import com.review.bot.rule.FileContext;
import com.review.bot.rule.Finding;
import com.review.bot.rule.RuleEngine;
import com.review.bot.rule.impl.LayerDependencyRule;
import com.review.bot.rule.impl.MaxMethodLengthRule;
import com.review.bot.rule.impl.NoFieldInjectionRule;
import com.review.bot.rule.impl.NoSystemOutRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RulesTest {

    private final ReviewProperties props =
            new ReviewProperties("t", null, null, List.of(), Map.of(
                    "max-method-length", new ReviewProperties.RuleConfig(true, null, Map.of("max-lines", "5"))));

    private final RuleEngine engine = new RuleEngine(List.of(
            new NoSystemOutRule(), new NoFieldInjectionRule(), new MaxMethodLengthRule(props),
            new LayerDependencyRule()), props);

    private static final String SRC = """
            package demo;
            class Foo {
                @Autowired private Bar bar;
                void a() {
                    System.out.println("hi");
                }
                void longOne() {
                    int x = 1;
                    x++;
                    x++;
                    x++;
                    x++;
                }
            }
            """;

    static Set<Integer> allLines() {
        return IntStream.rangeClosed(1, 40).boxed().collect(Collectors.toSet());
    }

    @Test
    void findsAllThreeRules_mostSevereFirst() {
        List<Finding> f = engine.review(new FileContext("src/main/java/demo/Foo.java", SRC, allLines()));
        assertThat(f).extracting(Finding::ruleId)
                .containsExactly("no-system-out", "no-field-injection", "max-method-length");
        assertThat(f).extracting(Finding::line).containsExactly(5, 3, 7);
    }

    @Test
    void ignoresUnchangedLines() {
        List<Finding> f = engine.review(new FileContext("src/main/java/demo/Foo.java", SRC, Set.of(5)));
        assertThat(f).extracting(Finding::ruleId).containsExactly("no-system-out");
    }

    @Test
    void skipsTestCodeForProductionOnlyRules() {
        List<Finding> f = engine.review(new FileContext("src/test/java/demo/Foo.java", SRC, allLines()));
        assertThat(f).extracting(Finding::ruleId).doesNotContain("no-system-out", "no-field-injection");
    }

    @Test
    void diffPositionsCountFromFirstHunk() {
        String patch = """
                @@ -1,3 +1,4 @@
                 line1
                +added2
                 line3
                 line4
                @@ -10,2 +11,3 @@
                 line11
                +added12
                -removed
                """.stripTrailing();
        Map<Integer, Integer> pos = DiffParser.addedLinePositions(patch);
        assertThat(pos).containsExactly(Map.entry(2, 2), Map.entry(12, 7));
    }
}
