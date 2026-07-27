package cloud.bamsongi.albammate.global.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 나노초 측정값을 기계 판독 가능한 밀리초 백분위로 바꾼다. */
public final class BenchmarkStatistics {

    private BenchmarkStatistics() {}

    public static Summary summarize(List<Long> elapsedNanos) {
        Objects.requireNonNull(elapsedNanos, "elapsedNanos");
        if (elapsedNanos.isEmpty()) {
            throw new IllegalArgumentException("at least one measurement is required");
        }
        List<Long> sorted = new ArrayList<>(elapsedNanos.size());
        for (Long elapsed : elapsedNanos) {
            if (elapsed == null || elapsed < 0) {
                throw new IllegalArgumentException("elapsed time must be non-negative");
            }
            sorted.add(elapsed);
        }
        Collections.sort(sorted);
        return new Summary(
                sorted.size(),
                toMillis(sorted.get(nearestRankIndex(sorted.size(), 0.50))),
                toMillis(sorted.get(nearestRankIndex(sorted.size(), 0.95))),
                toMillis(sorted.get(sorted.size() - 1)));
    }

    private static int nearestRankIndex(int sampleCount, double percentile) {
        return Math.max(0, (int) Math.ceil(sampleCount * percentile) - 1);
    }

    private static double toMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    public record Summary(int sampleCount, double p50Millis, double p95Millis, double maxMillis) {}
}
