package cloud.bamsongi.albammate.infra.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

class JdbcShedLockTaskLockTest {

	@Test
	void 유효한_잠금_시간을_그대로_LockConfiguration에_전달한다() {
		AtomicReference<LockConfiguration> receivedConfiguration = new AtomicReference<>();
		LockProvider lockProvider = lockConfiguration -> {
			receivedConfiguration.set(lockConfiguration);
			return Optional.of(() -> {});
		};
		JdbcShedLockTaskLock taskLock = new JdbcShedLockTaskLock(
			lockProvider, Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

		ScheduledTaskLock.LockExecution execution = taskLock.tryExecute(
			"duration-boundary-test", Duration.ofSeconds(5), Duration.ofSeconds(5), () -> {});

		assertEquals(true, execution.acquired());
		assertEquals(Duration.ofSeconds(5), receivedConfiguration.get().getLockAtMostFor());
		assertEquals(Duration.ofSeconds(5), receivedConfiguration.get().getLockAtLeastFor());
	}

	@Test
	void 최소_잠금_시간이_최대_잠금_시간을_초과하면_Provider에_전달하지_않는다() {
		LockProvider lockProvider = lockConfiguration -> {
			throw new AssertionError("invalid duration must not reach provider");
		};
		JdbcShedLockTaskLock taskLock = new JdbcShedLockTaskLock(
			lockProvider, Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

		assertThrows(IllegalArgumentException.class, () -> taskLock.tryExecute(
			"invalid-duration-test", Duration.ofSeconds(5), Duration.ofSeconds(6), () -> {}));
	}

	@Test
	void Error는_스케줄_실패로_변환하지_않고_그대로_전파한다() {
		LockProvider lockProvider = lockConfiguration -> Optional.of(() -> {});
		JdbcShedLockTaskLock taskLock = new JdbcShedLockTaskLock(
			lockProvider, Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
		AssertionError expected = new AssertionError("fatal scheduler error");

		AssertionError actual = assertThrows(AssertionError.class,
			() -> taskLock.tryExecute("error-propagation-test", Duration.ofSeconds(1), () -> {
				throw expected;
			}));

		assertEquals(expected, actual);
	}
}
