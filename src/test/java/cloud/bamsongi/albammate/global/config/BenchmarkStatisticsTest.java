package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class BenchmarkStatisticsTest {

    @Test
    void 측정값을_정렬해_p50_p95와_max를_계산한다() {
        BenchmarkStatistics.Summary summary =
                BenchmarkStatistics.summarize(
                        List.of(5_000_000L, 1_000_000L, 3_000_000L, 2_000_000L));

        assertEquals(4, summary.sampleCount());
        assertEquals(2.0, summary.p50Millis());
        assertEquals(5.0, summary.p95Millis());
        assertEquals(5.0, summary.maxMillis());
    }

    @Test
    void 빈_측정값과_음수_측정값을_거절한다() {
        assertThrows(
                IllegalArgumentException.class, () -> BenchmarkStatistics.summarize(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkStatistics.summarize(List.of(1L, -1L)));
    }
}
