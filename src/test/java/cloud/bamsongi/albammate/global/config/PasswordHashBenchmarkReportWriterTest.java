package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PasswordHashBenchmarkReportWriterTest {

    @Test
    void 보고서가_기계_판독_JSON으로_직렬화되고_민감한_값을_포함하지_않는다(@TempDir Path temporaryDirectory)
            throws Exception {
        PasswordHashBenchmarkReport report =
                new PasswordHashBenchmarkReport(
                        "2026-07-27T00:00:00Z",
                        new PasswordHashBenchmarkReport.EnvironmentMetadata(
                                "21", "21+", "Linux", "6", "amd64", 2, 512),
                        new PasswordHashBenchmarkReport.BenchmarkSettings(
                                "bcrypt", List.of(10), 1, 1, 1, 10, 4, 1_000),
                        List.of(
                                new PasswordHashBenchmarkReport.CostMeasurement(
                                        10,
                                        1,
                                        1,
                                        new BenchmarkStatistics.Summary(1, 10, 10, 10),
                                        new BenchmarkStatistics.Summary(1, 10, 10, 10))),
                        List.of(
                                new PasswordHashBenchmarkReport.ConcurrencyScenarioResult(
                                        5, 1, 4, 4, 4, 1, 4, null, null, true, true)),
                        new PasswordHashBenchmarkReport.BenchmarkEvaluation(
                                1_000, List.of(10), "MEASUREMENT_ONLY", "measurement only"),
                        "MEASUREMENT_ONLY");
        PasswordHashBenchmarkReportWriter writer = new PasswordHashBenchmarkReportWriter();

        String json = writer.toJson(report);
        Path output = temporaryDirectory.resolve("report.json");
        writer.write(report, output);

        assertTrue(json.contains("\"costMeasurements\""));
        assertTrue(json.contains("\"algorithm\" : \"bcrypt\""));
        assertFalse(json.contains("outputPath"));
        assertFalse(json.contains("result.json"));
        assertTrue(json.contains("MEASUREMENT_ONLY"));
        assertFalse(json.contains("benchmark-only-password"));
        assertFalse(json.contains("{bcrypt}benchmark-hash"));
        assertTrue(Files.readString(output).equals(json));
    }

    @Test
    void 출력_경로는_보고서와_완료_메시지에_노출되지_않고_쓰기_실패는_일반화한다(@TempDir Path temporaryDirectory) {
        PasswordHashBenchmarkReport report = minimalReport();
        PasswordHashBenchmarkReportWriter writer = new PasswordHashBenchmarkReportWriter();
        Path outputDirectory = temporaryDirectory.resolve("private-output");
        assertTrue(outputDirectory.toFile().mkdir());

        IllegalStateException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class,
                        () -> PasswordHashBenchmark.writeReport(writer, report, outputDirectory));

        assertEquals("could not write benchmark report", exception.getMessage());
        assertFalse(exception.getMessage().contains(outputDirectory.toString()));
        assertFalse(PasswordHashBenchmark.completionMessage().contains(outputDirectory.toString()));
    }

    private PasswordHashBenchmarkReport minimalReport() {
        return new PasswordHashBenchmarkReport(
                "2026-07-27T00:00:00Z",
                new PasswordHashBenchmarkReport.EnvironmentMetadata(
                        "21", "21+", "Linux", "6", "amd64", 2, 512),
                new PasswordHashBenchmarkReport.BenchmarkSettings(
                        "bcrypt", List.of(10), 1, 1, 1, 10, 4, 1_000),
                List.of(),
                List.of(),
                new PasswordHashBenchmarkReport.BenchmarkEvaluation(
                        1_000, List.of(), "MEASUREMENT_ONLY", "measurement only"),
                "MEASUREMENT_ONLY");
    }
}
