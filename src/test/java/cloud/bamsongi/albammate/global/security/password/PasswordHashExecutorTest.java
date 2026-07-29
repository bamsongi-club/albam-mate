package cloud.bamsongi.albammate.global.security.password;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

class PasswordHashExecutorTest {

	@Test
	void 해시_작업이_예외를_던져도_슬롯을_반환한다() {
		StubLimiter limiter = new StubLimiter();
		PasswordHashExecutor executor = new PasswordHashExecutor(limiter);

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
			});

		RateLimitExceededException exception = assertThrows(
			RateLimitExceededException.class,
			() -> executor.execute(executions::incrementAndGet));

		assertEquals(1, exception.getRetryAfterSeconds());
		assertEquals(0, executions.get());
	}

	private static final class StubLimiter implements PasswordHashConcurrencyLimiter {

		private final AtomicInteger closedPermits = new AtomicInteger();

		@Override
		public Optional<PasswordHashPermit> tryAcquire() {
			return Optional.of(closedPermits::incrementAndGet);
		}
	}
}
