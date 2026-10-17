package com.review.bot.metrics;

import com.review.bot.metrics.ManualBaseline.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualBaselineTest {

    @TempDir
    Path tmp;

    private Path csv(String content) throws IOException {
        Path file = tmp.resolve("baseline.csv");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void readsRows_skippingCommentsAndBlankLines_evenWithWindowsLineEndings() throws IOException {
        List<Row> rows = ManualBaseline.read(csv("# timed without the bot\r\n"
                + "sample,reviewer,minutes_to_decision,decision,issues_reported,issues_with_location,issues_with_fix,review_rounds\r\n"
                + "\r\n"
                + "03-errors-block, asha, 14.5, BLOCK, 3, 2, 1, 1\r\n"));

        assertThat(rows).containsExactly(new Row("03-errors-block", "asha", 14.5, "BLOCK", 3, 2, 1, 1));
    }

    @Test
    void theTemplateInDocsIsValidAndEmpty() throws IOException {
        assertThat(ManualBaseline.read(Path.of("docs/ac8/manual-baseline.csv"))).isEmpty();
    }

    @Test
    void columnsMayComeInAnyOrder() throws IOException {
        List<Row> rows = ManualBaseline.read(csv(
                "reviewer,sample,decision,minutes_to_decision,review_rounds,issues_with_fix,issues_with_location,issues_reported\n"
                        + "ben,samples-rejudge,APPROVE,30,3,4,5,6\n"));

        assertThat(rows.get(0)).isEqualTo(new Row("samples-rejudge", "ben", 30, "APPROVE", 6, 5, 4, 3));
    }

    @Test
    void missingColumn_isRejectedWithItsName() throws IOException {
        Path file = csv("sample,reviewer,minutes_to_decision\n");

        assertThatThrownBy(() -> ManualBaseline.read(file))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("decision");
    }

    @Test
    void badNumber_isRejectedWithItsLine() throws IOException {
        Path file = csv("sample,reviewer,minutes_to_decision,decision,issues_reported,issues_with_location,issues_with_fix,review_rounds\n"
                + "03-errors-block,asha,ten,BLOCK,3,2,1,1\n");

        assertThatThrownBy(() -> ManualBaseline.read(file))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line 2");
    }
}
