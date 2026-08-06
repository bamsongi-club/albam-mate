package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

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

	private RoomRepository.DueRoomCandidate candidate(long roomId, Instant startAt) {
		RoomRepository.DueRoomCandidate candidate = mock(RoomRepository.DueRoomCandidate.class);
		when(candidate.getRoomId()).thenReturn(roomId);
		when(candidate.getStartAt()).thenReturn(startAt);
		return candidate;
	}
}
