package com.review.bot.review;

import com.review.bot.review.UnifiedDiff.FilePatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedDiffTest {

    private static final String GIT_DIFF = """
            diff --git a/src/Changed.java b/src/Changed.java
            index e8f0863..fe1cd0b 100644
            --- a/src/Changed.java
            +++ b/src/Changed.java
            @@ -1,3 +1,4 @@
             class Changed {
            +    int added;
                 int kept;
             }
            diff --git a/src/New.java b/src/New.java
            new file mode 100644
            index 0000000..3f31c92
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
            diff --git a/logo.png b/logo.png
            new file mode 100644
            Binary files /dev/null and b/logo.png differ
            diff --git a/src/Old.java b/src/Renamed.java
            similarity index 90%
            rename from src/Old.java
            rename to src/Renamed.java
            --- a/src/Old.java
            +++ b/src/Renamed.java
            @@ -1 +1 @@
            -class Old {}
            +class Renamed {}
            """;

    @Test
    void splitsGitDiffIntoOnePatchPerFile_skippingBinaryFiles() {
        List<FilePatch> patches = UnifiedDiff.parse(GIT_DIFF);

        assertThat(patches).extracting(FilePatch::path)
                .containsExactly("src/Changed.java", "src/New.java", "src/Gone.java", "src/Renamed.java");
        assertThat(patches.get(1).isAdded()).isTrue();
        assertThat(patches.get(2).isDeleted()).isTrue();
        assertThat(patches.get(3).oldPath()).isEqualTo("src/Old.java");
    }

    @Test
    void patchesHaveTheShapeOfTheGitHubFilesApi() {
        FilePatch changed = UnifiedDiff.parse(GIT_DIFF).get(0);

        assertThat(changed.hunks()).startsWith("@@ -1,3 +1,4 @@");
        assertThat(DiffParser.addedLinePositions(changed.hunks())).containsExactly(Map.entry(2, 2));
        assertThat(DiffParser.newSideLines(changed.hunks()))
                .containsExactly(Map.entry(1, "class Changed {"), Map.entry(2, "    int added;"),
                        Map.entry(3, "    int kept;"), Map.entry(4, "}"));
    }

    @Test
    void readsPlainDiffU_withTimestampsAndNoGitHeader() {
        String diff = """
                --- src/A.java\t2024-05-01 10:00:00.000000000 +0200
                +++ src/A.java\t2024-05-02 10:00:00.000000000 +0200
                @@ -1 +1,2 @@
                 class A {}
                +// added
                --- src/B.java\t2024-05-01 10:00:00.000000000 +0200
                +++ src/B.java\t2024-05-02 10:00:00.000000000 +0200
                @@ -1 +1 @@
                -class B {}
                +class B { }
                """;
        assertThat(UnifiedDiff.parse(diff)).extracting(FilePatch::path).containsExactly("src/A.java", "src/B.java");
    }

    @Test
    void removedLineThatLooksLikeAFileHeaderStaysInsideItsHunk() {
        // "--- a" / "+++ b" here are a removed and an added line ("-- a", "++ b"), not a new file.
        String diff = """
                diff --git a/notes.md b/notes.md
                --- a/notes.md
                +++ b/notes.md
                @@ -1,2 +1,2 @@
                 title
                --- a
                +++ b
                """;
        List<FilePatch> patches = UnifiedDiff.parse(diff);

        assertThat(patches).extracting(FilePatch::path).containsExactly("notes.md");
        assertThat(DiffParser.newSideLines(patches.get(0).hunks())).containsExactly(
                Map.entry(1, "title"), Map.entry(2, "++ b"));
    }

    @Test
    void keepsNoNewlineMarkerWithItsHunk() {
        String diff = """
                diff --git a/a.txt b/a.txt
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -old
                \\ No newline at end of file
                +new
                \\ No newline at end of file
                diff --git a/b.txt b/b.txt
                --- a/b.txt
                +++ b/b.txt
                @@ -1 +1 @@
                -x
                +y
                """;
        List<FilePatch> patches = UnifiedDiff.parse(diff);

        assertThat(patches).extracting(FilePatch::path).containsExactly("a.txt", "b.txt");
        assertThat(patches.get(0).hunks()).endsWith("+new\n\\ No newline at end of file");
    }

    @Test
    void crlfDiffIsReadLikeLf() {
        String diff = "--- a/x.txt\r\n+++ b/x.txt\r\n@@ -1 +1,2 @@\r\n keep\r\n+add\r\n";
        assertThat(DiffParser.newSideLines(UnifiedDiff.parse(diff).get(0).hunks()))
                .containsExactly(Map.entry(1, "keep"), Map.entry(2, "add"));
    }

    @Test
    void textWithoutFileChangesHasNoPatches() {
        assertThat(UnifiedDiff.parse("hello\nthis is not a diff\n")).isEmpty();
    }
}
