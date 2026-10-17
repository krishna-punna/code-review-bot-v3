package com.review.bot.review;

import com.review.bot.review.ChangeSet.ChangedFile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeSetTest {

    private static final String DIFF = """
            diff --git a/src/Changed.java b/src/Changed.java
            --- a/src/Changed.java
            +++ b/src/Changed.java
            @@ -1,3 +1,4 @@
             class Changed {
            +    int added;
                 int kept;
             }
            diff --git a/src/New.java b/src/New.java
            new file mode 100644
            --- /dev/null
            +++ b/src/New.java
            @@ -0,0 +1,2 @@
            +class New {
            +}
            diff --git a/src/Gone.java b/src/Gone.java
            deleted file mode 100644
            --- a/src/Gone.java
            +++ /dev/null
            @@ -1 +0,0 @@
            -class Gone {}
            """;

    private static final String CHANGED = "class Changed {\n    int added;\n    int kept;\n}\n";

    @Test
    void diffWithContent_reviewsOnlyAddedLines_andRebuildsAddedFiles() {
        ChangeSet change = ChangeSet.fromDiff("t", DIFF, path -> Optional.ofNullable(Map.of("src/Changed.java", CHANGED).get(path)));

        assertThat(change.files()).extracting(ChangedFile::path).containsExactly("src/Changed.java", "src/New.java");
        assertThat(change.files().get(0).changedLines()).containsExactly(2);
        assertThat(change.files().get(1).content()).isEqualTo("class New {\n}\n");
        assertThat(change.files().get(1).changedLines()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void modifiedFileWithoutContent_isRejected() {
        assertThatThrownBy(() -> ChangeSet.fromDiff("t", DIFF, path -> Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No content for modified file src/Changed.java");
    }

    @Test
    void contentFromAnotherVersion_isRejected() {
        String stale = "class Changed {\n    int kept;\n}\n";
        assertThatThrownBy(() -> ChangeSet.fromDiff("t", DIFF, path -> Optional.of(stale)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("doesn't match the content of src/Changed.java at line 2");
    }

    @Test
    void textThatIsNotADiff_isRejectedRatherThanApproved() {
        assertThatThrownBy(() -> ChangeSet.fromDiff("t", "please review my code", path -> Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No file changes found");
    }

    @Test
    void wholeFiles_marksEveryLineChanged_inPathOrder() {
        ChangeSet change = ChangeSet.wholeFiles("t", Map.of("b/B.java", "a\nb\n", "a/A.java", "x\n"));

        assertThat(change.files()).extracting(ChangedFile::path).containsExactly("a/A.java", "b/B.java");
        assertThat(change.files().get(1).changedLines()).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void request_withoutDiffOrFiles_isRejected() {
        assertThatThrownBy(() -> new ReviewRequest(null, null, null).toChangeSet())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nothing to review");
    }

    @Test
    void request_withFilesOnly_reviewsThemInFull() {
        ChangeSet change = new ReviewRequest(" Sample ", null, Map.of("A.java", "class A {}\n")).toChangeSet();

        assertThat(change.title()).isEqualTo("Sample");
        assertThat(change.files().get(0).changedLines()).containsExactly(1);
    }
}
