package cloud.bamsongi.albammate.room.statuscorrection;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;

class RoomStatusCorrectionBoundedCoordinatorTest {

	private static final Instant CUTOFF = Instant.parse("2026-08-06T00:00:00Z");

	@Test
	void 한_ROOM의_실패를_격리하고_남은_ROOM을_처리한_뒤_실패_ID까지_cursor를_전진한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector selector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), selector, progressStore);
		RoomStatusCorrectionProgressStore.ProgressSnapshot claimed = snapshot(1L, null, null);
		when(selector.select(any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), eq(10)))
			.thenReturn(List.of(candidate(10L), candidate(20L), candidate(30L)), List.of());
		doThrow(new IllegalStateException("fixture failure")).when(executor).correctRoom(20L, CUTOFF);
		when(progressStore.advanceCursor(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class), anyLong()))
			.thenAnswer(invocation -> Optional.of(snapshot(
				invocation.getArgument(0, RoomStatusCorrectionProgressStore.ProgressSnapshot.class).progressVersion()
					+ 1,
				invocation.getArgument(1, Instant.class), invocation.getArgument(2, Long.class))));
		when(progressStore.wrap(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class)))
			.thenReturn(Optional.of(snapshot(5L, null, null)));

		coordinator.correctBoundedDueRooms(CUTOFF, claimed, 10);

		verify(executor).correctRoom(10L, CUTOFF);
		verify(executor).correctRoom(20L, CUTOFF);
		verify(executor).correctRoom(30L, CUTOFF);
		verify(progressStore, times(3)).advanceCursor(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class), anyLong());
		verify(progressStore).wrap(any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class));
	}

	@Test
	void stale_cursor_CAS가_거절되면_남은_후보를_처리하지_않고_현재_실행을_끝낸다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector selector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), selector, progressStore);
		RoomStatusCorrectionProgressStore.ProgressSnapshot claimed = snapshot(1L, null, null);
		when(selector.select(claimed, 10)).thenReturn(List.of(candidate(10L), candidate(20L)));
		when(progressStore.advanceCursor(claimed, CUTOFF.minusSeconds(1), 10L)).thenReturn(Optional.empty());

		coordinator.correctBoundedDueRooms(CUTOFF, claimed, 10);

		verify(executor).correctRoom(10L, CUTOFF);
		verify(executor, never()).correctRoom(20L, CUTOFF);
		verify(progressStore, never()).wrap(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class));
	}

	private RoomStatusCorrectionCandidateSelector.DueRoomCandidate candidate(long roomId) {
		return new RoomStatusCorrectionCandidateSelector.DueRoomCandidate(roomId, CUTOFF.minusSeconds(roomId));
	}

	private RoomStatusCorrectionProgressStore.ProgressSnapshot snapshot(
		long version, Instant cursorDueAt, Long cursorRoomId) {
		return new RoomStatusCorrectionProgressStore.ProgressSnapshot(
			CUTOFF, cursorDueAt, cursorRoomId, version, 1L);
	}
}
