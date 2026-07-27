package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BenchmarkOptionsTest {

    @Test
    void 프로퍼티와_인자로_cost와_반복_설정을_조절할_수_있다() {
        BenchmarkOptions options =
                BenchmarkOptions.parse(
                        new String[] {"--costs=12-14", "--samples=2", "--output=build/custom.json"},
                        Map.of("benchmark.warmup", "2", "benchmark.concurrencySamples", "3"));

        assertEquals(java.util.List.of(12, 13, 14), options.costs());
        assertEquals(2, options.warmupSamples());
        assertEquals(2, options.measurementSamples());
        assertEquals(3, options.concurrencySamples());
        assertEquals(Path.of("build/custom.json"), options.output());
    }

    @Test
    void cost와_반복_수의_범위를_검증한다() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[] {"--costs=9"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[] {"--samples=0"}));
    }

    @Test
    void 비용과_각_반복_수의_상한을_생성자와_parse에서_동일하게_검증한다() {
        assertEquals(
                BenchmarkOptions.MAX_COST,
                new BenchmarkOptions(
                                java.util.List.of(BenchmarkOptions.MAX_COST),
                                BenchmarkOptions.MAX_WARMUP_SAMPLES,
                                BenchmarkOptions.MAX_MEASUREMENT_SAMPLES,
                                BenchmarkOptions.MAX_CONCURRENCY_SAMPLES,
                                BenchmarkOptions.MAX_COST,
                                Path.of("build/report.json"))
                        .costs()
                        .getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BenchmarkOptions(
                                java.util.List.of(17), 1, 1, 1, 10, Path.of("build/report.json")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BenchmarkOptions(
                                java.util.List.of(10),
                                BenchmarkOptions.MAX_WARMUP_SAMPLES + 1,
                                1,
                                1,
                                10,
                                Path.of("build/report.json")));
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[] {"--costs=17"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[] {"--warmup=4"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[] {"--samples=11"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[] {"--concurrency-samples=4"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[0], Map.of("benchmark.warmup", "4")));
        assertThrows(
                IllegalArgumentException.class,
                () -> BenchmarkOptions.parse(new String[0], Map.of("benchmark.samples", "11")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BenchmarkOptions.parse(
                                new String[0], Map.of("benchmark.concurrencySamples", "4")));
    }
}
