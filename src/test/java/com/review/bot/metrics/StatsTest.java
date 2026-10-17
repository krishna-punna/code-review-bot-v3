package com.review.bot.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatsTest {

    @Test
    void oddCount_medianIsTheMiddleValue() {
        Stats s = Stats.of(List.of(5.0, 1.0, 3.0));

        assertThat(s.count()).isEqualTo(3);
        assertThat(s.min()).isEqualTo(1.0);
        assertThat(s.median()).isEqualTo(3.0);
        assertThat(s.max()).isEqualTo(5.0);
    }

    @Test
    void evenCount_medianIsTheMeanOfTheMiddleTwo() {
        assertThat(Stats.of(List.of(4.0, 1.0, 2.0, 3.0)).median()).isEqualTo(2.5);
    }

    @Test
    void p90_isTheNearestRank() {
        List<Double> oneToTen = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
        assertThat(Stats.of(oneToTen).p90()).isEqualTo(9.0);
        assertThat(Stats.of(List.of(7.0)).p90()).isEqualTo(7.0);
    }

    @Test
    void noValues_givesZeros() {
        assertThat(Stats.of(List.of())).isEqualTo(new Stats(0, 0, 0, 0, 0));
    }
}
