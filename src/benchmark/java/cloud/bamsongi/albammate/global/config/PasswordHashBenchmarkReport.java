package cloud.bamsongi.albammate.global.config;

import java.util.List;

/** 비밀번호 benchmark 결과의 공개 필드만 담는 보고서 모델이다. */
public record PasswordHashBenchmarkReport(
        String measuredAtUtc,
        EnvironmentMetadata environment,
        BenchmarkSettings settings,
        List<CostMeasurement> costMeasurements,
        List<ConcurrencyScenarioResult> concurrencyScenarios,
        BenchmarkEvaluation evaluation,
        String validationStatus) {

    public PasswordHashBenchmarkReport {
        costMeasurements = List.copyOf(costMeasurements);
        concurrencyScenarios = List.copyOf(concurrencyScenarios);
    }

    public record EnvironmentMetadata(
            String javaVersion,
            String javaRuntime,
            String operatingSystem,
            String operatingSystemVersion,
            String architecture,
            int availableProcessors,
            long maxHeapBytes) {}

    public record BenchmarkSettings(
            String algorithm,
            List<Integer> costs,
            int warmupSamples,
            int measurementSamples,
            int concurrencySamples,
            int concurrencyCost,
            int hashSlots,
            long thresholdMillis) {

        public BenchmarkSettings {
            costs = List.copyOf(costs);
        }
    }

    public record CostMeasurement(
            int cost,
            int warmupSamples,
            int measurementSamples,
            BenchmarkStatistics.Summary encodeLatency,
            BenchmarkStatistics.Summary matchesLatency) {}

    public record BenchmarkEvaluation(
            long latencyThresholdMillis,
            List<Integer> costsWithinP95Threshold,
            String status,
            String note) {

        public BenchmarkEvaluation {
            costsWithinP95Threshold = List.copyOf(costsWithinP95Threshold);
        }
    }

    public record ConcurrencyScenarioResult(
            int concurrency,
            int samples,
            int maxConcurrentSlots,
            int expectedAllowed,
            int allowedCount,
            int rejectedImmediatelyCount,
            int hashExecutionCount,
            BenchmarkStatistics.Summary allowedLatency,
            BenchmarkStatistics.Summary rejectedLatency,
            boolean allSlotsReturned,
            boolean expectedCountsObserved) {}
}
