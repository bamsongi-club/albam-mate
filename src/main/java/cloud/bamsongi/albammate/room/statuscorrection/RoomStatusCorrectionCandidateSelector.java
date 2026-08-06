package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** 세 시간 경계의 ROOM ID를 하나의 결정적 due 순서로 합친다. */
@Component
class RoomStatusCorrectionCandidateSelector {

	private final RoomRepository roomRepository;

	RoomStatusCorrectionCandidateSelector(RoomRepository roomRepository) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
	}

	List<DueRoomCandidate> select(
		RoomStatusCorrectionProgressStore.ProgressSnapshot progress, int candidateLimit) {
		Objects.requireNonNull(progress, "progress");
		if (candidateLimit < 1) {
			throw new IllegalArgumentException("ROOM 상태 보정 후보 수는 양수여야 합니다.");
		}

		Instant turnCutoff = Objects.requireNonNull(progress.turnCutoff(), "turnCutoff");
		Instant cursorDueAt = progress.cursorDueAt();
		Long cursorRoomId = progress.cursorRoomId();
		boolean hasCursor = cursorDueAt != null;
		Pageable limit = PageRequest.of(0, candidateLimit);
		List<DueRoomCandidate> candidates = new ArrayList<>();

		addStartBoundaryCandidates(candidates, turnCutoff, cursorDueAt, cursorRoomId, hasCursor, limit);
		addFinishBoundaryCandidates(candidates, turnCutoff, cursorDueAt, cursorRoomId, hasCursor, limit);

		return candidates.stream()
			.sorted(Comparator.comparing(DueRoomCandidate::dueAt).thenComparing(DueRoomCandidate::roomId))
			.limit(candidateLimit)
			.toList();
	}

	private void addStartBoundaryCandidates(
		List<DueRoomCandidate> candidates,
		Instant turnCutoff,
		Instant cursorDueAt,
		Long cursorRoomId,
		boolean hasCursor,
		Pageable limit) {
		roomRepository.findRecruitingDueRoomCandidates(turnCutoff, cursorDueAt, cursorRoomId, hasCursor, limit)
			.forEach(candidate -> candidates.add(toCandidate(candidate, candidate.getStartAt())));
		roomRepository.findClosedWaitingDueRoomCandidates(turnCutoff, cursorDueAt, cursorRoomId, hasCursor, limit)
			.forEach(candidate -> candidates.add(toCandidate(candidate, candidate.getStartAt())));
	}

	private void addFinishBoundaryCandidates(
		List<DueRoomCandidate> candidates,
		Instant turnCutoff,
		Instant cursorDueAt,
		Long cursorRoomId,
		boolean hasCursor,
		Pageable limit) {
		Instant finishBoundaryStartAt = turnCutoff.minus(Room.AUTOMATIC_FINISH_AFTER_START);
		Instant cursorFinishStartAt = cursorDueAt == null
			? null
			: cursorDueAt.minus(Room.AUTOMATIC_FINISH_AFTER_START);
		roomRepository.findClosedFinishDueRoomCandidates(
			finishBoundaryStartAt, cursorFinishStartAt, cursorRoomId, hasCursor, limit)
			.forEach(candidate -> candidates.add(
				toCandidate(candidate, candidate.getStartAt().plus(Room.AUTOMATIC_FINISH_AFTER_START))));
	}

	private DueRoomCandidate toCandidate(RoomRepository.DueRoomCandidate candidate, Instant dueAt) {
		return new DueRoomCandidate(candidate.getRoomId(), dueAt);
	}

	record DueRoomCandidate(Long roomId, Instant dueAt) {

		DueRoomCandidate {
			if (roomId == null || roomId < 1) {
				throw new IllegalArgumentException("ROOM 상태 보정 후보 ID는 양수여야 합니다.");
			}
			Objects.requireNonNull(dueAt, "dueAt");
		}
	}
}
