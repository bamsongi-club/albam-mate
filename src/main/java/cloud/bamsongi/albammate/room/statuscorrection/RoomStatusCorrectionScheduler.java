package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;
import lombok.extern.slf4j.Slf4j;

/** 요청이 없는 방도 같은 상태 보정 규칙으로 주기적으로 정리한다. */
@Component
@Slf4j
public class RoomStatusCorrectionScheduler implements Trigger {

	static final Duration BASE_DELAY = Duration.ofMinutes(15);
	static final Duration MAX_SCHEDULE_JITTER = Duration.ofMinutes(3);
	static final long SECOND_ATTEMPT_MAX_DELAY_MILLIS = 250;
	static final long THIRD_ATTEMPT_MAX_DELAY_MILLIS = 500;

	private final ScheduledTaskLock scheduledTaskLock;
	private final RoomStatusCorrectionCoordinator coordinator;
	private final RoomStatusCorrectionProgressStore progressStore;
	private final RoomStatusCorrectionProperties properties;
	private final Clock clock;

	RoomStatusCorrectionScheduler(
		ScheduledTaskLock scheduledTaskLock,
		RoomStatusCorrectionCoordinator coordinator,
		RoomStatusCorrectionProgressStore progressStore,
		RoomStatusCorrectionProperties properties,
		Clock clock) {
		this.scheduledTaskLock = Objects.requireNonNull(scheduledTaskLock, "scheduledTaskLock");
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.progressStore = Objects.requireNonNull(progressStore, "progressStore");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	long elapsedNanos() {
		return System.nanoTime();
	}

	long nextJitterMillis(long maxInclusive) {
		return ThreadLocalRandom.current().nextLong(maxInclusive + 1);
	}

	void sleepBeforeRetry(long delayMillis) {
		sleep(delayMillis);
	}

	/** 현재 시각을 한 번 얻어 due 방의 상태를 보정하고, 스케줄러 경로만 충돌 재시도에 지연을 둔다. */
	public void correctDueRooms() {
		Instant requestTime = Instant.now(clock);
		ScheduledTaskLock.LockExecution lockExecution = scheduledTaskLock.tryExecute(
			properties.getLockName(), properties.getLockAtMostFor(), () -> {
				correctDueRooms(requestTime);
			});
		if (!lockExecution.acquired()) {
			log.debug("event=room_state_reconciliation_lock_skipped lockName={}", properties.getLockName());
		}
	}

	private void correctDueRooms(Instant requestTime) {
		long startedAtNanos = elapsedNanos();
		try {
			Integer candidateLimit = properties.getCandidateLimit();
			if (candidateLimit == null) {
				log.warn("event=room_status_correction_skipped reason=candidate_limit_missing");
				return;
			}
			Integer maxBatchesPerRun = properties.getMaxBatchesPerRun();
			RoomStatusCorrectionProgressStore.ProgressSnapshot progress = progressStore.claimExecution(requestTime);
			RoomStatusCorrectionCoordinator.BoundedCorrectionResult result = coordinator.correctBoundedDueRooms(
				requestTime, progress, candidateLimit, maxBatchesPerRun, this::waitBeforeRetry);
			if (result.changedCount() > 0) {
				log.info("event=room_state_reconciliation_completed changedCount={}", result.changedCount());
			} else {
				log.debug("event=room_state_reconciliation_completed changedCount={}", result.changedCount());
			}
			if (result.hasRemainingCandidates()) {
				log.warn("event=room_status_correction_batch_limit_reached candidateLimit={} maxBatchesPerRun={}",
					candidateLimit, maxBatchesPerRun);
			}
		} catch (BusinessException exception) {
			if (exception.getErrorCode() != ErrorCode.ROOM_CONCURRENT_MODIFICATION) {
				log.warn("event=room_state_reconciliation_failed");
			}
			throw exception;
		} catch (RuntimeException exception) {
			log.warn("event=room_state_reconciliation_failed");
			throw exception;
		} finally {
			warnIfExecutionSlow(elapsedNanos() - startedAtNanos);
		}
	}

	private void warnIfExecutionSlow(long durationNanos) {
		Duration duration = Duration.ofNanos(durationNanos);
		if (duration.compareTo(properties.getExecutionWarningThreshold()) > 0) {
			log.warn("event=room_status_correction_execution_slow durationMs={} thresholdMs={}",
				duration.toMillis(), properties.getExecutionWarningThreshold().toMillis());
		}
	}

	/** 이전 실행 완료 시각을 기준으로 15분 뒤에 0~3분의 full jitter를 더해 다음 실행을 예약한다. */
	@Override
	public Instant nextExecution(TriggerContext triggerContext) {
		Objects.requireNonNull(triggerContext, "triggerContext");
		Instant anchor = triggerContext.lastCompletion();
		if (anchor == null) {
			anchor = triggerContext.getClock().instant();
		}
		long jitterMillis = nextJitterMillis(properties.getTriggerJitter().toMillis());
		return anchor.plus(properties.getTriggerDelay()).plusMillis(jitterMillis);
	}

	private void waitBeforeRetry(int nextAttempt) {
		long maxDelayMillis = switch (nextAttempt) {
			case 2 -> SECOND_ATTEMPT_MAX_DELAY_MILLIS;
			case 3 -> THIRD_ATTEMPT_MAX_DELAY_MILLIS;
			default -> throw new IllegalArgumentException("지원하지 않는 재시도 횟수");
		};
		sleepBeforeRetry(nextJitterMillis(maxDelayMillis));
	}

	private static void sleep(long delayMillis) {
		try {
			Thread.sleep(delayMillis);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("방 상태 보정 재시도가 중단되었습니다.", exception);
		}
	}

	@FunctionalInterface
	interface JitterSource {

		long nextMillis(long maxInclusive);
	}

	@FunctionalInterface
	interface Sleeper {

		void sleep(long delayMillis);
	}
}
