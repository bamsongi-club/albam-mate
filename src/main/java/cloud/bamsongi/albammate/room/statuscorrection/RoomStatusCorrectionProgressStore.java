package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 실행 세대와 progress version을 함께 비교하는 ROOM 전용 cursor CAS 경계다. */
@Component
class RoomStatusCorrectionProgressStore {

	private final RoomStatusCorrectionProgressRepository progressRepository;

	RoomStatusCorrectionProgressStore(RoomStatusCorrectionProgressRepository progressRepository) {
		this.progressRepository = Objects.requireNonNull(progressRepository, "progressRepository");
	}

	@Transactional
	ProgressSnapshot claimExecution(Instant requestTime) {
		RoomStatusCorrectionProgress progress = progressRepository
			.findByJobNameForUpdate(RoomStatusCorrectionProgress.JOB_NAME)
			.orElseThrow(() -> new IllegalStateException("ROOM 상태 보정 progress 행이 없습니다."));
		Instant nextTurnCutoff = nextTurnCutoff(progress, requestTime);
		if (progressRepository.claimExecution(RoomStatusCorrectionProgress.JOB_NAME, requestTime) != 1) {
			throw new IllegalStateException("ROOM 상태 보정 progress 행을 점유하지 못했습니다.");
		}
		return new ProgressSnapshot(
			nextTurnCutoff, progress.getCursorDueAt(), progress.getCursorRoomId(),
			progress.getProgressVersion() + 1, progress.getExecutionGeneration() + 1);
	}

	@Transactional(readOnly = true)
	ProgressSnapshot current() {
		return ProgressSnapshot.from(progressRepository.findCurrent());
	}

	@Transactional
	Optional<ProgressSnapshot> advanceCursor(ProgressSnapshot expected, Instant cursorDueAt, long cursorRoomId) {
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(cursorDueAt, "cursorDueAt");
		if (cursorRoomId < 1 || expected.turnCutoff() == null || cursorDueAt.isAfter(expected.turnCutoff())) {
			throw new IllegalArgumentException("유효하지 않은 ROOM 상태 보정 cursor입니다.");
		}
		int updated = progressRepository.advanceCursor(
			RoomStatusCorrectionProgress.JOB_NAME,
			expected.progressVersion(),
			expected.executionGeneration(),
			cursorDueAt,
			cursorRoomId);
		if (updated == 0) {
			return Optional.empty();
		}
		return Optional.of(new ProgressSnapshot(
			expected.turnCutoff(), cursorDueAt, cursorRoomId,
			expected.progressVersion() + 1, expected.executionGeneration()));
	}

	@Transactional
	Optional<ProgressSnapshot> wrap(ProgressSnapshot expected, Instant nextTurnCutoff) {
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(nextTurnCutoff, "nextTurnCutoff");
		if (expected.turnCutoff() == null || !nextTurnCutoff.isAfter(expected.turnCutoff())) {
			throw new IllegalArgumentException("다음 ROOM 상태 보정 cutoff는 현재 cutoff보다 뒤여야 합니다.");
		}
		int updated = progressRepository.wrap(
			RoomStatusCorrectionProgress.JOB_NAME,
			expected.progressVersion(),
			expected.executionGeneration(),
			nextTurnCutoff);
		if (updated == 0) {
			return Optional.empty();
		}
		return Optional.of(new ProgressSnapshot(
			nextTurnCutoff, null, null,
			expected.progressVersion() + 1, expected.executionGeneration()));
	}

	private Instant nextTurnCutoff(RoomStatusCorrectionProgress progress, Instant requestTime) {
		Instant currentTurnCutoff = progress.getTurnCutoff();
		if (currentTurnCutoff != null && progress.getCursorDueAt() != null) {
			return currentTurnCutoff;
		}
		return currentTurnCutoff == null || requestTime.isAfter(currentTurnCutoff) ? requestTime : currentTurnCutoff;
	}

	record ProgressSnapshot(
		Instant turnCutoff,
		Instant cursorDueAt,
		Long cursorRoomId,
		long progressVersion,
		long executionGeneration) {

		private static ProgressSnapshot from(RoomStatusCorrectionProgress progress) {
			return new ProgressSnapshot(
				progress.getTurnCutoff(),
				progress.getCursorDueAt(),
				progress.getCursorRoomId(),
				progress.getProgressVersion(),
				progress.getExecutionGeneration());
		}
	}
}
