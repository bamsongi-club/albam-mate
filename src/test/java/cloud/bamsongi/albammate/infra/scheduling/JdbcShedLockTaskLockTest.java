package cloud.bamsongi.albammate.infra.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.javacrumbs.shedlock.core.LockProvider;

class JdbcShedLockTaskLockTest {

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
