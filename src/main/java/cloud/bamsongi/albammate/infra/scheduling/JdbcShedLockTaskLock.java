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
		Objects.requireNonNull(lockName, "lockName");
		Objects.requireNonNull(lockAtMostFor, "lockAtMostFor");
		Objects.requireNonNull(task, "task");
		try {
			LockingTaskExecutor.TaskResult<Void> result = lockingTaskExecutor.executeWithLock(
				(LockingTaskExecutor.TaskWithResult<Void>)() -> {
					task.run();
					return null;
				},
				new LockConfiguration(Instant.now(clock), lockName, lockAtMostFor, Duration.ZERO));
			return result.wasExecuted() ? LockExecution.acquiredResult() : LockExecution.skippedResult();
		} catch (RuntimeException | Error exception) {
			throw exception;
		} catch (Throwable throwable) {
			throw new IllegalStateException("ShedLock task execution failed", throwable);
		}
	}
}
