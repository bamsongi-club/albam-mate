package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntConsumer;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import lombok.extern.slf4j.Slf4j;

/** 트랜잭션 경계 밖에서 낙관 락 충돌만 제한적으로 재시도한다. */
@Service
@Slf4j
public class RoomStatusCorrectionCoordinator {

	private final RoomStatusCorrectionExecutor executor;
	private final RoomOptimisticLockRetrier retrier;
	private final RoomStatusCorrectionCandidateSelector candidateSelector;
	private final RoomStatusCorrectionProgressStore progressStore;

	public RoomStatusCorrectionCoordinator(
		RoomStatusCorrectionExecutor executor,
		RoomOptimisticLockRetrier retrier,
		RoomStatusCorrectionCandidateSelector candidateSelector,
		RoomStatusCorrectionProgressStore progressStore) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.retrier = Objects.requireNonNull(retrier, "retrier");
		this.candidateSelector = Objects.requireNonNull(candidateSelector, "candidateSelector");
		this.progressStore = Objects.requireNonNull(progressStore, "progressStore");
	}

	/** 단건 상태 보정을 최대 세 개의 독립 트랜잭션으로 시도한다. */
	public void correctRoom(Long roomId, Instant requestTime) {
		Objects.requireNonNull(roomId, "roomId");
		Objects.requireNonNull(requestTime, "requestTime");
		correctRoom(roomId, requestTime, ignoredAttempt -> {});
	}

	/** 목록·내 모임 필터와 페이지 계산 전에 due 방 전체를 보정한다. */
	public int correctDueRooms(Instant requestTime) {
		return correctDueRooms(requestTime, ignoredAttempt -> {});
	}

	/** 스케줄러처럼 재시도 전 지연이 필요한 호출자만 시도별 지연을 주입한다. */
	int correctDueRooms(Instant requestTime, IntConsumer beforeRetry) {
		Objects.requireNonNull(requestTime, "requestTime");
		Objects.requireNonNull(beforeRetry, "beforeRetry");
		return retrier.execute(
			() -> executor.correctDueRooms(requestTime),
			"room_state_reconciliation_retry", null, beforeRetry);
	}

	/** 고정 cutoff를 유지하면서 제한된 후보를 처리하고, ROOM별 결과와 cursor를 분리한다. */
	BoundedCorrectionResult correctBoundedDueRooms(
		Instant requestTime,
		RoomStatusCorrectionProgressStore.ProgressSnapshot claimedProgress,
		int candidateLimit,
		int maxBatchesPerRun) {
		return correctBoundedDueRooms(
			requestTime, claimedProgress, candidateLimit, maxBatchesPerRun, ignoredAttempt -> {});
	}

	/** 스케줄러 경로의 ROOM 충돌 재시도 전에만 지연 hook을 적용한다. */
	BoundedCorrectionResult correctBoundedDueRooms(
		Instant requestTime,
		RoomStatusCorrectionProgressStore.ProgressSnapshot claimedProgress,
		int candidateLimit,
		int maxBatchesPerRun,
		IntConsumer beforeRetry) {
		Objects.requireNonNull(requestTime, "requestTime");
		Objects.requireNonNull(claimedProgress, "claimedProgress");
		Objects.requireNonNull(beforeRetry, "beforeRetry");
		if (candidateLimit < 1) {
			throw new IllegalArgumentException("ROOM 상태 보정 후보 수는 양수여야 합니다.");
		}
		if (maxBatchesPerRun < 1) {
			throw new IllegalArgumentException("ROOM 상태 보정 실행당 최대 배치 수는 양수여야 합니다.");
		}

		RoomStatusCorrectionProgressStore.ProgressSnapshot progress = claimedProgress;
		int changedCount = 0;
		for (int batchIndex = 0; batchIndex < maxBatchesPerRun; batchIndex++) {
			List<RoomStatusCorrectionCandidateSelector.DueRoomCandidate> candidates = candidateSelector
				.select(progress, candidateLimit);
			if (candidates.isEmpty()) {
				progressStore.wrap(progress, nextTurnCutoff(requestTime, progress.turnCutoff()));
				return new BoundedCorrectionResult(changedCount, false);
			}

			for (RoomStatusCorrectionCandidateSelector.DueRoomCandidate candidate : candidates) {
				try {
					if (correctRoom(candidate.roomId(), requestTime, beforeRetry)) {
						changedCount++;
					}
				} catch (BusinessException exception) {
					// 계약된 업무 거절과 낙관 락 소진은 호출자 오류 계약으로만 전달한다.
				} catch (RuntimeException exception) {
					log.warn("event=room_status_reconciliation_room_failed roomId={} useCase={} reasonCode={}",
						candidate.roomId(), "ROOM_STATUS_CORRECTION", "UNEXPECTED_FAILURE");
				}

				Optional<RoomStatusCorrectionProgressStore.ProgressSnapshot> advanced = progressStore.advanceCursor(
					progress, candidate.dueAt(), candidate.roomId());
				if (advanced.isEmpty()) {
					return new BoundedCorrectionResult(changedCount, false);
				}
				progress = advanced.get();
			}
		}

		if (!candidateSelector.select(progress, 1).isEmpty()) {
			return new BoundedCorrectionResult(changedCount, true);
		}
		progressStore.wrap(progress, nextTurnCutoff(requestTime, progress.turnCutoff()));
		return new BoundedCorrectionResult(changedCount, false);
	}

	record BoundedCorrectionResult(int changedCount, boolean hasRemainingCandidates) {
	}

	private boolean correctRoom(Long roomId, Instant requestTime, IntConsumer beforeRetry) {
		return retrier.execute(
			() -> {
				return executor.correctRoom(roomId, requestTime);
			},
			"room_state_reconciliation_retry", roomId, beforeRetry);
	}

	private Instant nextTurnCutoff(Instant requestTime, Instant currentTurnCutoff) {
		if (requestTime.isAfter(currentTurnCutoff)) {
			return requestTime;
		}
		// PostgreSQL TIMESTAMP WITH TIME ZONE은 마이크로초 정밀도이므로 다음 cutoff도 DB에 저장 가능한 최소 단위로 넘긴다.
		return currentTurnCutoff.plusNanos(1_000);
	}
}
