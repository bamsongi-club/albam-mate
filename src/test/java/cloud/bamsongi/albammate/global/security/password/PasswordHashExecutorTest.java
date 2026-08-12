package cloud.bamsongi.albammate.global.security.password;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PasswordHashExecutorTest {

	@Test
	void 해시_작업이_예외를_던져도_슬롯을_반환한다() {
		StubLimiter limiter = new StubLimiter();
		PasswordHashExecutor executor = new PasswordHashExecutor(limiter, null);

		assertThrows(
			IllegalStateException.class,
			() -> executor.execute(
				() -> {
					throw new IllegalStateException("hash failed");
				}));
		assertEquals(1, limiter.closedPermits.get());
	}

	@Test
	void 슬롯이_없으면_해시_콜백을_실행하지_않고_Retry_After_1을_던진다() {
		AtomicInteger executions = new AtomicInteger();
		PasswordHashExecutor executor = new PasswordHashExecutor(
			new PasswordHashConcurrencyLimiter() {
				@Override
				public Optional<PasswordHashPermit> tryAcquire() {
					return Optional.empty();
				}
			}, null);

		RateLimitExceededException exception = assertThrows(
			RateLimitExceededException.class,
			() -> executor.execute(executions::incrementAndGet));

		assertEquals(1, exception.getRetryAfterSeconds());
		assertEquals(0, executions.get());
	}

	@Test
	void T4_T6_bcrypt_슬롯_포화는_콜백을_실행하지_않고_원인과_permit_경계를_기록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AtomicInteger executions = new AtomicInteger();
		PasswordHashExecutor executor = new PasswordHashExecutor(
			new PasswordHashConcurrencyLimiter() {
				@Override
				public Optional<PasswordHashPermit> tryAcquire() {
					return Optional.empty();
				}
			}, new AuthNotificationMeasurementRecorder(registry));

		assertThrows(RateLimitExceededException.class, () -> executor.execute(executions::incrementAndGet));

		assertEquals(0, executions.get());
		assertEquals(1, registry.find("auth.login.stage.duration").tag("stage", "bcrypt-permit").timer().count());
		assertEquals(1, registry.find("auth.login.rejections").tag("source", "bcrypt-slot").counter().count());
	}

	@Test
	void T6_bcrypt_permit은_해시_작업과_permit_반환까지_전체_점유를_기록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		PasswordHashExecutor executor = new PasswordHashExecutor(new StubLimiter(),
			new AuthNotificationMeasurementRecorder(registry));

		executor.execute(() -> {
			java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20));
			return "hashed";
		});

		assertEquals(1, registry.find("auth.login.stage.duration").tag("stage", "bcrypt-permit").timer().count());
		assertEquals(true, registry.find("auth.login.stage.duration").tag("stage", "bcrypt-permit").timer()
			.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 20);
	}

	private static final class StubLimiter implements PasswordHashConcurrencyLimiter {

		private final AtomicInteger closedPermits = new AtomicInteger();

		@Override
		public Optional<PasswordHashPermit> tryAcquire() {
			return Optional.of(closedPermits::incrementAndGet);
		}
	}
}
