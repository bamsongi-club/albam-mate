package cloud.bamsongi.albammate.global.config;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.InMemoryPasswordHashConcurrencyLimiter;
import cloud.bamsongi.albammate.global.security.PasswordHashConcurrencyLimiter;
import cloud.bamsongi.albammate.global.security.PasswordHashExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 동시 해시 슬롯 경계를 짧고 재현 가능하게 측정한다. */
public final class PasswordHashConcurrencyScenarioRunner {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(1);
    private static final String BENCHMARK_PASSWORD = "benchmark-only-password";

    private PasswordHashConcurrencyScenarioRunner() {}

    public static PasswordHashBenchmarkReport.ConcurrencyScenarioResult run(
            PasswordEncoder encoder, int hashSlots, int concurrency, int samples) {
        return run(encoder, hashSlots, concurrency, samples, DEFAULT_TIMEOUT);
    }

    static PasswordHashBenchmarkReport.ConcurrencyScenarioResult run(
            PasswordEncoder encoder,
            int hashSlots,
            int concurrency,
            int samples,
            Duration timeout) {
        if (hashSlots < 1 || concurrency < 1 || samples < 1) {
            throw new IllegalArgumentException(
                    "hash slots, concurrency and samples must be positive");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("scenario timeout must be positive");
        }
        List<Long> allowedLatencies = new ArrayList<>();
        List<Long> rejectedLatencies = new ArrayList<>();
        int allowedCount = 0;
        int rejectedCount = 0;
        int hashExecutionCount = 0;
        boolean allSlotsReturned = true;

        for (int sample = 0; sample < samples; sample++) {
            ScenarioSample result = runOnce(encoder, hashSlots, concurrency, timeout);
            allowedLatencies.addAll(result.allowedLatencies());
            rejectedLatencies.addAll(result.rejectedLatencies());
            allowedCount += result.allowedCount();
            rejectedCount += result.rejectedCount();
            hashExecutionCount += result.hashExecutionCount();
            allSlotsReturned &= result.allSlotsReturned();
        }

        int expectedAllowed = Math.min(hashSlots, concurrency) * samples;
        int expectedRejected = Math.max(0, concurrency - hashSlots) * samples;
        boolean expectedCountsObserved =
                allowedCount == expectedAllowed
                        && rejectedCount == expectedRejected
                        && hashExecutionCount == expectedAllowed;
        return new PasswordHashBenchmarkReport.ConcurrencyScenarioResult(
                concurrency,
                samples,
                hashSlots,
                expectedAllowed,
                allowedCount,
                rejectedCount,
                hashExecutionCount,
                BenchmarkStatistics.summarize(allowedLatencies),
                rejectedLatencies.isEmpty()
                        ? null
                        : BenchmarkStatistics.summarize(rejectedLatencies),
                allSlotsReturned,
                expectedCountsObserved);
    }

    private static ScenarioSample runOnce(
            PasswordEncoder encoder, int hashSlots, int concurrency, Duration timeout) {
        AuthenticationRequestProtectionProperties properties =
                new AuthenticationRequestProtectionProperties();
        properties.setHashSlots(hashSlots);
        PasswordHashConcurrencyLimiter limiter =
                new InMemoryPasswordHashConcurrencyLimiter(properties);
        PasswordHashExecutor executor = new PasswordHashExecutor(limiter);
        ExecutorService workers = Executors.newFixedThreadPool(concurrency, daemonThreadFactory());
        CyclicBarrier start = new CyclicBarrier(concurrency + 1);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch attempted = new CountDownLatch(concurrency);
        CountDownLatch admissionDecisions = new CountDownLatch(concurrency);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Long> allowedLatencies = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Long> rejectedLatencies = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < concurrency; i++) {
                AtomicBoolean admissionRecorded = new AtomicBoolean();
                futures.add(
                        workers.submit(
                                () -> {
                                    try {
                                        ready.countDown();
                                        awaitBarrier(start, "start barrier", timeout);
                                        long startedAt = System.nanoTime();
                                        attempted.countDown();
                                        try {
                                            executor.execute(
                                                    () -> {
                                                        recordAdmission(
                                                                admissionDecisions,
                                                                admissionRecorded);
                                                        accepted.incrementAndGet();
                                                        awaitLatch(
                                                                release, "release latch", timeout);
                                                        encoder.encode(BENCHMARK_PASSWORD);
                                                        return null;
                                                    });
                                            allowedLatencies.add(System.nanoTime() - startedAt);
                                        } catch (RateLimitExceededException exception) {
                                            recordAdmission(admissionDecisions, admissionRecorded);
                                            rejected.incrementAndGet();
                                            rejectedLatencies.add(System.nanoTime() - startedAt);
                                        } catch (Throwable throwable) {
                                            recordAdmission(admissionDecisions, admissionRecorded);
                                            failure.compareAndSet(null, throwable);
                                        }
                                    } catch (Throwable throwable) {
                                        recordAdmission(admissionDecisions, admissionRecorded);
                                        failure.compareAndSet(null, throwable);
                                    }
                                }));
            }
            awaitLatch(ready, "worker readiness", timeout);
            awaitBarrier(start, "start barrier", timeout);
            awaitLatch(attempted, "attempt completion", timeout);
            awaitLatch(admissionDecisions, "admission decision", timeout);
        } catch (RuntimeException | Error exception) {
            release.countDown();
            cleanupWorkers(workers, futures);
            throw exception;
        } finally {
            release.countDown();
        }

        try {
            for (Future<?> future : futures) {
                future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            Throwable workerFailure = failure.get();
            if (workerFailure != null) {
                throw new IllegalStateException("password hash scenario failed", workerFailure);
            }
            return new ScenarioSample(
                    accepted.get(),
                    rejected.get(),
                    accepted.get(),
                    List.copyOf(allowedLatencies),
                    List.copyOf(rejectedLatencies),
                    limiter.currentConcurrent() == 0);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("password hash scenario interrupted", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("password hash scenario timed out", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("password hash scenario failed", exception.getCause());
        } finally {
            cleanupWorkers(workers, futures);
        }
    }

    private static void awaitBarrier(CyclicBarrier barrier, String name, Duration timeout) {
        try {
            barrier.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " interrupted", exception);
        } catch (java.util.concurrent.BrokenBarrierException
                | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException(name + " timed out", exception);
        }
    }

    private static void awaitLatch(CountDownLatch latch, String name, Duration timeout) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(name + " timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " interrupted", exception);
        }
    }

    private static void recordAdmission(
            CountDownLatch admissionDecisions, AtomicBoolean admissionRecorded) {
        if (admissionRecorded.compareAndSet(false, true)) {
            admissionDecisions.countDown();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger threadNumber = new AtomicInteger();
        return runnable -> {
            Thread thread =
                    new Thread(
                            runnable, "password-hash-benchmark-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void cleanupWorkers(ExecutorService workers, List<Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(CLEANUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                // Benchmark workers are daemon threads; do not hold the calling process for a
                // CPU-bound encoder that does not observe interruption.
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record ScenarioSample(
            int allowedCount,
            int rejectedCount,
            int hashExecutionCount,
            List<Long> allowedLatencies,
            List<Long> rejectedLatencies,
            boolean allSlotsReturned) {}
}
