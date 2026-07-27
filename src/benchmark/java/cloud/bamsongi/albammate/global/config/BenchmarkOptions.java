package cloud.bamsongi.albammate.global.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 비밀번호 benchmark 실행 설정이다. 운영 설정과 분리해 실행 때만 사용한다. */
public record BenchmarkOptions(
        List<Integer> costs,
        int warmupSamples,
        int measurementSamples,
        int concurrencySamples,
        int concurrencyCost,
        Path output) {

    public static final List<Integer> DEFAULT_COSTS = List.of(10, 11, 12, 13, 14);
    public static final int DEFAULT_WARMUP_SAMPLES = 1;
    public static final int DEFAULT_MEASUREMENT_SAMPLES = 3;
    public static final int DEFAULT_CONCURRENCY_SAMPLES = 1;
    public static final int DEFAULT_CONCURRENCY_COST = 10;
    public static final int MAX_COST = 16;
    public static final int MAX_WARMUP_SAMPLES = 3;
    public static final int MAX_MEASUREMENT_SAMPLES = 10;
    public static final int MAX_CONCURRENCY_SAMPLES = 3;
    public static final Path DEFAULT_OUTPUT = Path.of("build/reports/password-hash-benchmark.json");

    public BenchmarkOptions {
        costs = List.copyOf(Objects.requireNonNull(costs, "costs"));
        if (costs.isEmpty()) {
            throw new IllegalArgumentException("at least one bcrypt cost is required");
        }
        costs.forEach(BenchmarkOptions::validateCost);
        validateSampleCount("warmup samples", warmupSamples, MAX_WARMUP_SAMPLES);
        validateSampleCount("measurement samples", measurementSamples, MAX_MEASUREMENT_SAMPLES);
        validateSampleCount("concurrency samples", concurrencySamples, MAX_CONCURRENCY_SAMPLES);
        validateCost(concurrencyCost);
        output = Objects.requireNonNull(output, "output");
    }

    public static BenchmarkOptions defaults() {
        return new BenchmarkOptions(
                DEFAULT_COSTS,
                DEFAULT_WARMUP_SAMPLES,
                DEFAULT_MEASUREMENT_SAMPLES,
                DEFAULT_CONCURRENCY_SAMPLES,
                DEFAULT_CONCURRENCY_COST,
                DEFAULT_OUTPUT);
    }

    public static BenchmarkOptions parse(String[] arguments) {
        return parse(arguments, System.getProperties());
    }

    static BenchmarkOptions parse(String[] arguments, Map<?, ?> properties) {
        BenchmarkOptions defaults = defaults();
        List<Integer> costs = parseCosts(property(properties, "benchmark.costs"), defaults.costs());
        int warmup =
                parsePositive(
                        property(properties, "benchmark.warmup"),
                        defaults.warmupSamples(),
                        "warmup samples",
                        MAX_WARMUP_SAMPLES);
        int samples =
                parsePositive(
                        property(properties, "benchmark.samples"),
                        defaults.measurementSamples(),
                        "measurement samples",
                        MAX_MEASUREMENT_SAMPLES);
        int concurrencySamples =
                parsePositive(
                        property(properties, "benchmark.concurrencySamples"),
                        defaults.concurrencySamples(),
                        "concurrency samples",
                        MAX_CONCURRENCY_SAMPLES);
        int concurrencyCost =
                parseCost(
                        property(properties, "benchmark.concurrencyCost"),
                        defaults.concurrencyCost(),
                        "concurrency cost");
        Path output =
                Path.of(
                        property(properties, "benchmark.output") == null
                                ? defaults.output().toString()
                                : property(properties, "benchmark.output"));

        for (String argument : arguments) {
            if (argument.equals("--help") || argument.equals("-h")) {
                continue;
            }
            int separator = argument.indexOf('=');
            if (!argument.startsWith("--") || separator < 3) {
                throw new IllegalArgumentException("unsupported benchmark argument: " + argument);
            }
            String key = argument.substring(2, separator);
            String value = argument.substring(separator + 1);
            switch (key) {
                case "costs" -> costs = parseCosts(value, defaults.costs());
                case "warmup" ->
                        warmup = parsePositive(value, warmup, "warmup samples", MAX_WARMUP_SAMPLES);
                case "samples" ->
                        samples =
                                parsePositive(
                                        value,
                                        samples,
                                        "measurement samples",
                                        MAX_MEASUREMENT_SAMPLES);
                case "concurrency-samples" ->
                        concurrencySamples =
                                parsePositive(
                                        value,
                                        concurrencySamples,
                                        "concurrency samples",
                                        MAX_CONCURRENCY_SAMPLES);
                case "concurrency-cost" ->
                        concurrencyCost = parseCost(value, concurrencyCost, "concurrency cost");
                case "output" -> output = Path.of(value);
                default ->
                        throw new IllegalArgumentException(
                                "unsupported benchmark argument: " + argument);
            }
        }
        return new BenchmarkOptions(
                costs, warmup, samples, concurrencySamples, concurrencyCost, output);
    }

    public static String usage() {
        return "Usage: passwordHashBenchmark [--costs=10,11,12,13,14] [--warmup=N] "
                + "[--samples=N] [--concurrency-samples=N] [--concurrency-cost=N] [--output=PATH]";
    }

    private static String property(Map<?, ?> properties, String key) {
        if (properties == null) {
            return null;
        }
        Object value = properties.get(key);
        return value == null ? null : value.toString();
    }

    private static List<Integer> parseCosts(String value, List<Integer> fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Set<Integer> parsed = new LinkedHashSet<>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (trimmed.matches("\\d+-\\d+")) {
                String[] range = trimmed.split("-");
                int start = parseCost(range[0], -1, "bcrypt cost");
                int end = parseCost(range[1], -1, "bcrypt cost");
                if (start > end) {
                    throw new IllegalArgumentException("bcrypt cost range must be ascending");
                }
                for (int cost = start; cost <= end; cost++) {
                    parsed.add(cost);
                }
            } else {
                parsed.add(parseCost(trimmed, -1, "bcrypt cost"));
            }
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("at least one bcrypt cost is required");
        }
        return new ArrayList<>(parsed);
    }

    private static int parsePositive(String value, int fallback, String name, int maximum) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            validateSampleCount(name, parsed, maximum);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a positive integer", exception);
        }
    }

    private static int parseCost(String value, int fallback, String name) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            validateCost(parsed);
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    name + " must be an integer between 10 and " + MAX_COST, exception);
        }
    }

    private static void validatePositive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void validateSampleCount(String name, int value, int maximum) {
        validatePositive(name, value);
        if (value > maximum) {
            throw new IllegalArgumentException(name + " must be at most " + maximum);
        }
    }

    private static void validateCost(int cost) {
        if (cost < 10 || cost > MAX_COST) {
            throw new IllegalArgumentException("bcrypt cost must be between 10 and " + MAX_COST);
        }
    }
}
