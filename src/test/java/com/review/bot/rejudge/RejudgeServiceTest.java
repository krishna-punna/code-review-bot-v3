package com.review.bot.rejudge;

import com.review.bot.config.ReviewProperties;
import com.review.bot.review.ChangeSet;
import com.review.bot.review.ChangeSet.ChangedFile;
import com.review.bot.review.ReviewResult;
import com.review.bot.review.ReviewService;
import com.review.bot.rule.Finding;
import com.review.bot.rule.RuleEngine;
import com.review.bot.rule.Severity;
import com.review.bot.rule.impl.NoFieldInjectionRule;
import com.review.bot.rule.impl.NoSystemOutRule;
import com.review.bot.verdict.Decision;
import com.review.bot.verdict.VerdictEngine;
import com.review.bot.verdict.VerdictProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** AC6: re-evaluate only what changed, mark findings resolved / still open / new, decide again. */
class RejudgeServiceTest {

    private static final String A = "src/main/java/demo/A.java";
    private static final String B = "src/main/java/demo/B.java";

    private static final String A_BLOCKED = """
            package demo;
            class A {
                void a() {
                    System.out.println("one");
                    System.out.println("two");
                }
            }
            """;
    private static final String A_PARTLY_FIXED = """
            package demo;
            class A {
                void a() {
                    System.out.println("two");
                    System.err.println("three");
                }
            }
            """;
    private static final String A_CLEAN = """
            package demo;
            class A {
                void a() {
                }
            }
            """;
    private static final String B_WARNING = """
            package demo;
            class B {
                @Autowired private Dep dep;
            }
            """;
    private static final String B_CLEAN = """
            package demo;
            class B {
                private final Dep dep;
                B(Dep dep) { this.dep = dep; }
            }
            """;

    private final ReviewProperties props = new ReviewProperties("t", null, null, List.of(), Map.of());
    private final RejudgeService service = service(props);

    private static RejudgeService service(ReviewProperties props) {
        RuleEngine engine = new RuleEngine(List.of(new NoSystemOutRule(), new NoFieldInjectionRule()), props);
        VerdictEngine verdicts = new VerdictEngine(VerdictProperties.defaults());
        return new RejudgeService(new ReviewService(engine, verdicts), engine, verdicts, props);
    }

    private static ChangedFile changed(String path, String content) {
        Set<Integer> all = IntStream.rangeClosed(1, (int) content.lines().count()).boxed().collect(Collectors.toSet());
        return new ChangedFile(path, content, all);
    }

    private static FileInput file(String path, String content) {
        return FileInput.of(changed(path, content));
    }

    /** Same key, but loading it fails the test: proves the file was not reviewed again. */
    private static FileInput notReloaded(String path, String content) {
        FileInput f = file(path, content);
        return new FileInput(f.path(), f.key(), () -> {
            throw new AssertionError(path + " was reviewed again although it didn't change");
        });
    }

    private RejudgeResult review(ReviewState previous, FileInput... files) throws IOException {
        return service.review("t", "", null, List.of(files), previous);
    }

    private static List<String> lines(List<Finding> findings) {
        return findings.stream().map(f -> f.ruleId() + "@" + f.location()).toList();
    }

    // ---- first review ---------------------------------------------------------------------------

    @Test
    void firstReview_isExactlyThePlainReview_plusTheState() throws IOException {
        RejudgeResult first = review(null, file(A, A_BLOCKED), file(B, B_WARNING));

        ReviewResult plain = new ReviewService(new RuleEngine(List.of(new NoSystemOutRule(), new NoFieldInjectionRule()), props),
                new VerdictEngine(VerdictProperties.defaults()))
                .review(new ChangeSet("t", List.of(changed(A, A_BLOCKED), changed(B, B_WARNING))));
        assertThat(first.rejudge()).isNull();
        assertThat(first.result()).isEqualTo(plain);
        assertThat(first.state().decision()).isEqualTo(Decision.BLOCK);
        assertThat(first.state().files()).containsOnlyKeys(A, B);
        assertThat(first.state().findings()).isEqualTo(plain.findings());
    }

    // ---- the fix-and-rejudge loop ---------------------------------------------------------------

    @Test
    void partialFix_reevaluatesOnlyTheChangedFile_andMarksEachFinding() throws IOException {
        ReviewState first = review(null, file(A, A_BLOCKED), file(B, B_WARNING)).state();

        RejudgeResult second = review(first, file(A, A_PARTLY_FIXED), notReloaded(B, B_WARNING));
        Rejudge r = second.rejudge();

        assertThat(r.reevaluated()).containsExactly(A);
        assertThat(r.carriedOver()).containsExactly(B);
        assertThat(r.fullReason()).isNull();
        assertThat(lines(r.resolved())).containsExactly("no-system-out@" + A + ":4");      // println("one")
        assertThat(lines(r.unresolved())).containsExactly(
                "no-system-out@" + A + ":4",                                                  // println("two"), moved up a line
                "no-field-injection@" + B + ":3");                                            // carried over unchanged
        assertThat(lines(r.newFindings())).containsExactly("no-system-out@" + A + ":5");    // System.err
        assertThat(r.previousDecision()).isEqualTo(Decision.BLOCK);
        assertThat(second.result().verdict().decision()).isEqualTo(Decision.BLOCK);
        assertThat(second.result().findings()).hasSize(3);
    }

    @Test
    void everythingFixed_resolvesAll_andApproves() throws IOException {
        ReviewState first = review(null, file(A, A_BLOCKED), file(B, B_WARNING)).state();

        RejudgeResult second = review(first, file(A, A_CLEAN), file(B, B_CLEAN));

        assertThat(second.rejudge().resolved()).hasSize(3);
        assertThat(second.rejudge().unresolved()).isEmpty();
        assertThat(second.rejudge().newFindings()).isEmpty();
        assertThat(second.result().verdict().decision()).isEqualTo(Decision.APPROVE);
        assertThat(second.result().gatePassed()).isTrue();
        assertThat(second.state().findings()).isEmpty();
    }

    @Test
    void verdictFollowsTheFindingsStillOpen() throws IOException {
        ReviewState first = review(null, file(A, A_BLOCKED), file(B, B_WARNING)).state();

        RejudgeResult second = review(first, file(A, A_CLEAN), notReloaded(B, B_WARNING));

        assertThat(second.rejudge().previousDecision()).isEqualTo(Decision.BLOCK);
        assertThat(second.result().verdict().decision()).isEqualTo(Decision.REQUEST_CHANGES);
        assertThat(lines(second.result().verdict().drivingFindings())).containsExactly("no-field-injection@" + B + ":3");
    }

    @Test
    void sameFindingTwice_pairsByCount() throws IOException {
        String twice = "class A {\n  void a() {\n    System.out.println(x);\n    System.out.println(x);\n  }\n}\n";
        String once = "class A {\n  void a() {\n    System.out.println(x);\n  }\n}\n";
        ReviewState first = review(null, file(A, twice)).state();

        Rejudge r = review(first, file(A, once)).rejudge();

        assertThat(r.resolved()).hasSize(1);
        assertThat(r.unresolved()).hasSize(1);
        assertThat(r.newFindings()).isEmpty();
    }

    @Test
    void codeMovingDown_isStillTheSameFinding() throws IOException {
        ReviewState first = review(null, file(A, A_BLOCKED)).state();

        Rejudge r = review(first, file(A, A_BLOCKED.replace("class A {", "class A {\n\n\n"))).rejudge();

        assertThat(r.resolved()).isEmpty();
        assertThat(r.newFindings()).isEmpty();
        assertThat(lines(r.unresolved())).containsExactly("no-system-out@" + A + ":7", "no-system-out@" + A + ":8");
    }

    @Test
    void fileNoLongerInTheChange_hasItsFindingsResolved() throws IOException {
        ReviewState first = review(null, file(A, A_BLOCKED), file(B, B_WARNING)).state();

        Rejudge r = review(first, notReloaded(A, A_BLOCKED)).rejudge();

        assertThat(lines(r.resolved())).containsExactly("no-field-injection@" + B + ":3");
        assertThat(r.carriedOver()).containsExactly(A);
    }

    // ---- when nothing can be carried over -------------------------------------------------------

    @Test
    void changedRuleConfig_reevaluatesEveryFile() throws IOException {
        ReviewState first = review(null, file(A, A_BLOCKED), file(B, B_WARNING)).state();
        ReviewProperties stricter = new ReviewProperties("t", null, null, List.of(), Map.of(
                "no-field-injection", new ReviewProperties.RuleConfig(true, Severity.ERROR, null)));

        RejudgeResult second = service(stricter).review("t", "", null, List.of(file(A, A_BLOCKED), file(B, B_WARNING)), first);

        assertThat(second.rejudge().fullReason()).contains("rule configuration changed");
        assertThat(second.rejudge().reevaluated()).containsExactly(A, B);
        assertThat(second.rejudge().carriedOver()).isEmpty();
        assertThat(second.result().findings()).filteredOn(f -> f.ruleId().equals("no-field-injection"))
                .extracting(Finding::severity).containsExactly(Severity.ERROR);
    }

    @Test
    void movedBase_reevaluatesEveryFile() throws IOException {
        ReviewState first = service.review("t", "github:base1", "sha1", List.of(file(A, A_BLOCKED)), null).state();

        RejudgeResult second = service.review("t", "github:base2", "sha2", List.of(file(A, A_BLOCKED)), first);

        assertThat(second.rejudge().fullReason()).contains("base of the change moved");
        assertThat(second.rejudge().reevaluated()).containsExactly(A);
        assertThat(second.rejudge().previousCommit()).isEqualTo("sha1");
    }

    @Test
    void sameInputTwice_givesTheSameStateAndReport() throws IOException {
        RejudgeResult one = review(null, file(B, B_WARNING), file(A, A_BLOCKED));
        RejudgeResult two = review(null, file(A, A_BLOCKED), file(B, B_WARNING));

        assertThat(two.state()).isEqualTo(one.state());
        assertThat(RejudgeFormatter.markdown(review(one.state(), file(A, A_PARTLY_FIXED), file(B, B_WARNING))))
                .isEqualTo(RejudgeFormatter.markdown(review(two.state(), file(B, B_WARNING), file(A, A_PARTLY_FIXED))));
    }
}
