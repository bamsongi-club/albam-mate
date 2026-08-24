package cloud.bamsongi.albammate.global.scheduling;

import java.time.Duration;

/** 공용 스케줄이 외부 잠금 구현을 모르고 단일 실행만 요청하는 기술 포트다. */
public interface ScheduledTaskLock {

	LockExecution tryExecute(String lockName, Duration lockAtMostFor, Runnable task);

	default LockExecution tryExecute(
		String lockName, Duration lockAtMostFor, Duration lockAtLeastFor, Runnable task) {
		return tryExecute(lockName, lockAtMostFor, task);
	}

	record LockExecution(boolean acquired) {

		public static LockExecution acquiredResult() {
			return new LockExecution(true);
		}

		public static LockExecution skippedResult() {
			return new LockExecution(false);
		}
	}
}
