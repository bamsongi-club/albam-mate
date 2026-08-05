package cloud.bamsongi.albammate.infra.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

/** 공식 JDBC ShedLock Provider를 공용 스케줄 잠금 포트로 감싼다. */
@Component
class JdbcShedLockTaskLock implements ScheduledTaskLock {

	private final LockingTaskExecutor lockingTaskExecutor;
	private final Clock clock;

	JdbcShedLockTaskLock(LockProvider shedLockProvider, Clock clock) {
		this.lockingTaskExecutor = new DefaultLockingTaskExecutor(
			Objects.requireNonNull(shedLockProvider, "shedLockProvider"));
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public LockExecution tryExecute(String lockName, Duration lockAtMostFor, Runnable task) {
		return tryExecute(lockName, lockAtMostFor, Duration.ZERO, task);
	}

	@Override
	public LockExecution tryExecute(
		String lockName, Duration lockAtMostFor, Duration lockAtLeastFor, Runnable task) {
		Objects.requireNonNull(lockName, "lockName");
		Objects.requireNonNull(lockAtMostFor, "lockAtMostFor");
		Objects.requireNonNull(lockAtLeastFor, "lockAtLeastFor");
		Objects.requireNonNull(task, "task");
		validateLockDurations(lockAtMostFor, lockAtLeastFor);
		try {
			LockingTaskExecutor.TaskResult<Void> result = lockingTaskExecutor.executeWithLock(
				(LockingTaskExecutor.TaskWithResult<Void>)() -> {
					task.run();
					return null;
				},
				new LockConfiguration(Instant.now(clock), lockName, lockAtMostFor, lockAtLeastFor));
			return result.wasExecuted() ? LockExecution.acquiredResult() : LockExecution.skippedResult();
		} catch (RuntimeException | Error exception) {
			throw exception;
		} catch (Throwable throwable) {
			throw new IllegalStateException("ShedLock task execution failed", throwable);
		}
	}

	private void validateLockDurations(Duration lockAtMostFor, Duration lockAtLeastFor) {
		if (lockAtLeastFor.compareTo(lockAtMostFor) > 0) {
			throw new IllegalArgumentException("lockAtLeastFor must not exceed lockAtMostFor");
		}
	}
}
