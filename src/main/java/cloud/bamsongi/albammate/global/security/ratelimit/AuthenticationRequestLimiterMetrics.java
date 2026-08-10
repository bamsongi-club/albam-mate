package cloud.bamsongi.albammate.global.security.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;

/** 인증 요청 제한의 용량 사용률과 안전한 거절 원인만 기록한다. */
public final class AuthenticationRequestLimiterMetrics {

	private final Map<String, CapacityUsage> usages = new ConcurrentHashMap<>();
	private final MeterRegistry meterRegistry;
	private final LongSupplier ticker;

	public AuthenticationRequestLimiterMetrics(MeterRegistry meterRegistry) {
		this(meterRegistry, System::nanoTime);
	}

	AuthenticationRequestLimiterMetrics(MeterRegistry meterRegistry, LongSupplier ticker) {
		this.meterRegistry = java.util.Objects.requireNonNull(meterRegistry, "meterRegistry");
		this.ticker = java.util.Objects.requireNonNull(ticker, "ticker");
	}

	public static AuthenticationRequestLimiterMetrics global() {
		return new AuthenticationRequestLimiterMetrics(Metrics.globalRegistry);
	}

	public void recordUsage(String family, int registrations, int maximum, Duration window) {
		CapacityUsage usage = usages.computeIfAbsent(family, this::registerUsage);
		usage.record(registrations, maximum, window);
	}

	public void incrementRejection(String family, String reason) {
		Counter.builder("auth.request.limiter.rejections")
			.tags("family", family, "reason", reason)
			.register(meterRegistry)
			.increment();
	}

	private CapacityUsage registerUsage(String family) {
		CapacityUsage usage = new CapacityUsage(ticker);
		Gauge.builder("auth.request.limiter.capacity.utilization", usage, CapacityUsage::value)
			.tag("family", family)
			.register(meterRegistry);
		return usage;
	}

	private static final class CapacityUsage {

		private final AtomicInteger registrations = new AtomicInteger();
		private final AtomicInteger maximum = new AtomicInteger(1);
		private final AtomicLong observedAtNanos = new AtomicLong(Long.MIN_VALUE);
		private final AtomicLong windowNanos = new AtomicLong();
		private final LongSupplier ticker;

		private CapacityUsage(LongSupplier ticker) {
			this.ticker = ticker;
		}

		private void record(int registrations, int maximum, Duration window) {
			this.registrations.set(registrations);
			this.maximum.set(maximum);
			windowNanos.set(window.toNanos());
			observedAtNanos.set(ticker.getAsLong());
		}

		private double value() {
			long observedAt = observedAtNanos.get();
			if (observedAt == Long.MIN_VALUE || ticker.getAsLong() - observedAt >= windowNanos.get()) {
				return 0.0;
			}
			return (double)registrations.get() / maximum.get();
		}
	}
}
