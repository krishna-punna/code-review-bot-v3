package com.review.bot.metrics;

import java.util.List;

/** Summary of a set of measurements, such as milliseconds per review. */
public record Stats(int count, double min, double median, double p90, double max) {

    public static Stats of(List<Double> values) {
        if (values.isEmpty()) return new Stats(0, 0, 0, 0, 0);
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        double median = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
        double p90 = sorted.get((int) Math.ceil(0.9 * n) - 1);   // nearest rank
        return new Stats(n, sorted.get(0), median, p90, sorted.get(n - 1));
    }
}
