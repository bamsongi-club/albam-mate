package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@ExtendWith(MockitoExtension.class)
class RoomParticipationExecutorTest {

	private static final long ROOM_ID = 7L;
	private static final long USER_ID = 42L;
	private static final Instant REQUEST_TIME = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomRepository roomRepository;
	@Mock
	private ParticipationRepository participationRepository;
	@Mock
	private Room room;

	@Test
	void 최신_방을_읽고_신규_참가_관계와_카운터를_저장한다() {
		RoomParticipationExecutor executor = executor();
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		when(participationRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
			.thenReturn(Optional.empty());
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(room.getHostUserId()).thenReturn(1L);
		when(room.getActiveParticipantCount()).thenReturn(0, 1, 1);
		when(room.getCapacity()).thenReturn(2);
		when(room.getTotalParticipantCount()).thenReturn(2);
		when(room.getRemainingRecruitmentSeats()).thenReturn(1);
		when(room.getStartAt()).thenReturn(REQUEST_TIME.plusSeconds(3600));
		when(room.getId()).thenReturn(ROOM_ID);

		RoomParticipationResponse response = executor.participate(USER_ID, ROOM_ID, REQUEST_TIME);

		assertEquals(ROOM_ID, response.roomId());
		assertEquals(2, response.participantCount());
		assertEquals(1, response.remainingRecruitmentSeats());
		verify(room).reconcileStateAt(REQUEST_TIME);
		verify(room).addActiveParticipant();
		InOrder writes = inOrder(roomRepository, participationRepository);
		writes.verify(roomRepository).save(room);
		writes.verify(roomRepository).flush();
		writes.verify(participationRepository).save(any(Participation.class));
	}

	@Test
	void 취소된_방은_참가_저장_전에_ROOM_NOT_RECRUITING을_반환한다() {
		RoomParticipationExecutor executor = executor();
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		when(participationRepository.findByRoomIdAndUserId(ROOM_ID, USER_ID))
			.thenReturn(Optional.empty());
		when(room.getStatus()).thenReturn(RoomStatus.CANCELED);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.participate(USER_ID, ROOM_ID, REQUEST_TIME));

		assertEquals(ErrorCode.ROOM_NOT_RECRUITING, exception.getErrorCode());
		verify(room).reconcileStateAt(REQUEST_TIME);
		verify(participationRepository, never()).save(any(Participation.class));
	}

	@Test
	void 없는_방은_참가_관계_조회와_저장_전에_ROOM_NOT_FOUND로_종료한다() {
		RoomParticipationExecutor executor = executor();
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.participate(USER_ID, ROOM_ID, REQUEST_TIME));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(participationRepository);
	}

	private RoomParticipationExecutor executor() {
		return new RoomParticipationExecutor(roomRepository, participationRepository);
	}
}
