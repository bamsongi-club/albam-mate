package cloud.bamsongi.albammate.global.config;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Spring Security bcrypt 설정과 해시 슬롯 계약을 운영 유사 환경에서 측정한다. */
public final class PasswordHashBenchmark {

    private static final String BENCHMARK_PASSWORD = "benchmark-only-password";
    private static final long LATENCY_THRESHOLD_MILLIS = 1_000L;
    private static final int HASH_SLOTS = 4;

    private PasswordHashBenchmark() {}

    public static void main(String[] arguments) throws IOException {
        if (containsHelp(arguments)) {
            System.out.println(BenchmarkOptions.usage());
            return;
        }
        BenchmarkOptions options = BenchmarkOptions.parse(arguments);
        PasswordHashBenchmarkReport report = measure(options);
        PasswordHashBenchmarkReportWriter writer = new PasswordHashBenchmarkReportWriter();
        writeReport(writer, report, options.output());
        System.out.println(writer.toJson(report));
        System.out.println(completionMessage());
    }

    static PasswordHashBenchmarkReport measure(BenchmarkOptions options) {
        PasswordSecurityConfig config = new PasswordSecurityConfig();
        List<PasswordHashBenchmarkReport.CostMeasurement> measurements = new ArrayList<>();
        for (int cost : options.costs()) {
            PasswordEncoder encoder = encoderFor(config, cost);
            warmup(encoder, options.warmupSamples());
            List<Long> encodeLatencies = new ArrayList<>(options.measurementSamples());
            List<Long> matchesLatencies = new ArrayList<>(options.measurementSamples());
            for (int sample = 0; sample < options.measurementSamples(); sample++) {
                long encodeStartedAt = System.nanoTime();
                String hash = encoder.encode(BENCHMARK_PASSWORD);
                encodeLatencies.add(System.nanoTime() - encodeStartedAt);

                long matchesStartedAt = System.nanoTime();
                if (!encoder.matches(BENCHMARK_PASSWORD, hash)) {
                    throw new IllegalStateException("bcrypt self-check failed");
                }
                matchesLatencies.add(System.nanoTime() - matchesStartedAt);
            }
            measurements.add(
                    new PasswordHashBenchmarkReport.CostMeasurement(
                            cost,
                            options.warmupSamples(),
                            options.measurementSamples(),
                            BenchmarkStatistics.summarize(encodeLatencies),
                            BenchmarkStatistics.summarize(matchesLatencies)));
        }

        PasswordEncoder concurrencyEncoder = encoderFor(config, options.concurrencyCost());
        warmup(concurrencyEncoder, options.warmupSamples());
        // The runner creates a fresh limiter for every sample so each scenario starts at zero.
        List<PasswordHashBenchmarkReport.ConcurrencyScenarioResult> scenarios =
                List.of(
                        PasswordHashConcurrencyScenarioRunner.run(
                                concurrencyEncoder, HASH_SLOTS, 1, options.concurrencySamples()),
                        PasswordHashConcurrencyScenarioRunner.run(
                                concurrencyEncoder, HASH_SLOTS, 4, options.concurrencySamples()),
                        PasswordHashConcurrencyScenarioRunner.run(
                                concurrencyEncoder, HASH_SLOTS, 5, options.concurrencySamples()));

        List<Integer> costsWithinThreshold =
                measurements.stream()
                        .filter(
                                measurement ->
                                        measurement.encodeLatency().p95Millis()
                                                        <= LATENCY_THRESHOLD_MILLIS
                                                && measurement.matchesLatency().p95Millis()
                                                        <= LATENCY_THRESHOLD_MILLIS)
                        .map(PasswordHashBenchmarkReport.CostMeasurement::cost)
                        .toList();
        PasswordHashBenchmarkReport.BenchmarkEvaluation evaluation =
                new PasswordHashBenchmarkReport.BenchmarkEvaluation(
                        LATENCY_THRESHOLD_MILLIS,
                        costsWithinThreshold,
                        "MEASUREMENT_ONLY",
                        "Use these measurements with the ADR threshold; this task does not approve an operational cost.");
        PasswordHashBenchmarkReport.EnvironmentMetadata environment =
                new PasswordHashBenchmarkReport.EnvironmentMetadata(
                        System.getProperty("java.version"),
                        System.getProperty("java.runtime.version"),
                        System.getProperty("os.name"),
                        System.getProperty("os.version"),
                        System.getProperty("os.arch"),
                        Runtime.getRuntime().availableProcessors(),
                        Runtime.getRuntime().maxMemory());
        PasswordHashBenchmarkReport.BenchmarkSettings settings =
                new PasswordHashBenchmarkReport.BenchmarkSettings(
                        "bcrypt",
                        options.costs(),
                        options.warmupSamples(),
                        options.measurementSamples(),
                        options.concurrencySamples(),
                        options.concurrencyCost(),
                        HASH_SLOTS,
                        LATENCY_THRESHOLD_MILLIS);
        return new PasswordHashBenchmarkReport(
                Instant.now().toString(),
                environment,
                settings,
                measurements,
                scenarios,
                evaluation,
                "MEASUREMENT_ONLY");
    }

    private static PasswordEncoder encoderFor(PasswordSecurityConfig config, int cost) {
        PasswordSecurityProperties properties = new PasswordSecurityProperties();
        properties.setBcryptCost(cost);
        return config.passwordEncoder(properties);
    }

    private static void warmup(PasswordEncoder encoder, int samples) {
        for (int sample = 0; sample < samples; sample++) {
            String hash = encoder.encode(BENCHMARK_PASSWORD);
            if (!encoder.matches(BENCHMARK_PASSWORD, hash)) {
                throw new IllegalStateException("bcrypt warmup self-check failed");
            }
        }
    }

    private static boolean containsHelp(String[] arguments) {
        for (String argument : arguments) {
            if (argument.equals("--help") || argument.equals("-h")) {
                return true;
            }
        }
        return false;
    }

    static void writeReport(
            PasswordHashBenchmarkReportWriter writer,
            PasswordHashBenchmarkReport report,
            java.nio.file.Path output) {
        try {
            writer.write(report, output);
        } catch (IOException exception) {
            throw new IllegalStateException("could not write benchmark report");
        }
    }

    static String completionMessage() {
        return "Benchmark completed";
    }
}
