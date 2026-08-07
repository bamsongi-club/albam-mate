package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

class RoomStatusCorrectionCandidateSelectorTest {

	private static final Instant CUTOFF = Instant.parse("2026-08-06T00:00:00Z");

	@Test
	void turn_cutoff_이하의_세_경계를_논리_dueAt과_roomId_오름차순으로_제한_선별한다() {
		RoomRepository roomRepository = mock(RoomRepository.class);
		RoomStatusCorrectionCandidateSelector selector = new RoomStatusCorrectionCandidateSelector(roomRepository);
		RoomRepository.DueRoomCandidate recruitingCandidate = candidate(30L, CUTOFF.minusSeconds(30));
		RoomRepository.DueRoomCandidate waitingCandidate = candidate(20L, CUTOFF.minusSeconds(30));
		RoomRepository.DueRoomCandidate finishCandidate = candidate(
			10L, CUTOFF.minusSeconds(24 * 60 * 60 + 30));
		when(roomRepository.findRecruitingDueRoomCandidates(any(), any(), any(), anyBoolean(), any(Pageable.class)))
			.thenReturn(List.of(recruitingCandidate));
		when(roomRepository.findClosedWaitingDueRoomCandidates(any(), any(), any(), anyBoolean(), any(Pageable.class)))
			.thenReturn(List.of(waitingCandidate));
		when(roomRepository.findClosedFinishDueRoomCandidates(any(), any(), any(), anyBoolean(), any(Pageable.class)))
			.thenReturn(List.of(finishCandidate));

		List<RoomStatusCorrectionCandidateSelector.DueRoomCandidate> selected = selector.select(
			new RoomStatusCorrectionProgressStore.ProgressSnapshot(CUTOFF, null, null, 1L, 1L), 2);

		assertEquals(List.of(10L, 20L), selected.stream().map(candidate -> candidate.roomId()).toList());
		assertEquals(CUTOFF.minusSeconds(30), selected.getFirst().dueAt());
		verify(roomRepository).findRecruitingDueRoomCandidates(any(), any(), any(), anyBoolean(), any(Pageable.class));
		verify(roomRepository).findClosedWaitingDueRoomCandidates(any(), any(), any(), anyBoolean(),
			any(Pageable.class));
		verify(roomRepository).findClosedFinishDueRoomCandidates(any(), any(), any(), anyBoolean(),
			any(Pageable.class));
	}

	@Test
	void cursor가_없으면_종료_후보_조회에_24시간_경계와_빈_cursor를_전달한다() {
		RoomRepository roomRepository = mock(RoomRepository.class);
		stubNoCandidates(roomRepository);
		RoomStatusCorrectionCandidateSelector selector = new RoomStatusCorrectionCandidateSelector(roomRepository);

		selector.select(snapshot(null, null), 10);

		verify(roomRepository).findClosedFinishDueRoomCandidates(
			eq(CUTOFF.minus(Room.AUTOMATIC_FINISH_AFTER_START)),
			isNull(Instant.class),
			isNull(Long.class),
			eq(false),
			any(Pageable.class));
	}

	@Test
	void cursor가_있으면_종료_후보_조회에_논리_cursor를_시작시각_cursor로_변환해_전달한다() {
		RoomRepository roomRepository = mock(RoomRepository.class);
		stubNoCandidates(roomRepository);
		RoomStatusCorrectionCandidateSelector selector = new RoomStatusCorrectionCandidateSelector(roomRepository);
		Instant cursorDueAt = CUTOFF.minusSeconds(6 * 60 * 60);
		Long cursorRoomId = 77L;

		selector.select(snapshot(cursorDueAt, cursorRoomId), 10);

		verify(roomRepository).findClosedFinishDueRoomCandidates(
			eq(CUTOFF.minus(Room.AUTOMATIC_FINISH_AFTER_START)),
			eq(cursorDueAt.minus(Room.AUTOMATIC_FINISH_AFTER_START)),
			eq(cursorRoomId),
			eq(true),
			any(Pageable.class));
	}

	private void stubNoCandidates(RoomRepository roomRepository) {
		when(roomRepository.findRecruitingDueRoomCandidates(any(), any(), any(), anyBoolean(), any(Pageable.class)))
			.thenReturn(List.of());
		when(roomRepository.findClosedWaitingDueRoomCandidates(any(), any(), any(), anyBoolean(), any(Pageable.class)))
			.thenReturn(List.of());
		when(roomRepository.findClosedFinishDueRoomCandidates(any(), any(), any(), anyBoolean(), any(Pageable.class)))
			.thenReturn(List.of());
	}

	private RoomStatusCorrectionProgressStore.ProgressSnapshot snapshot(Instant cursorDueAt, Long cursorRoomId) {
		return new RoomStatusCorrectionProgressStore.ProgressSnapshot(
			CUTOFF, cursorDueAt, cursorRoomId, 1L, 1L);
	}

	private RoomRepository.DueRoomCandidate candidate(long roomId, Instant startAt) {
		RoomRepository.DueRoomCandidate candidate = mock(RoomRepository.DueRoomCandidate.class);
		when(candidate.getRoomId()).thenReturn(roomId);
		when(candidate.getStartAt()).thenReturn(startAt);
		return candidate;
	}
}
