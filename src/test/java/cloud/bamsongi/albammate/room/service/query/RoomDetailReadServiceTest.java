package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

@ExtendWith(MockitoExtension.class)
class RoomDetailReadServiceTest {

	@Mock
	private RoomRepository roomRepository;
	@Mock
	private ParticipationRepository participationRepository;
	@Mock
	private RoomWaitlistRepository roomWaitlistRepository;

	private RoomDetailReadService roomDetailReadService;

	@BeforeEach
	void setUp() {
		roomDetailReadService = new RoomDetailReadService(
			roomRepository, participationRepository, roomWaitlistRepository);
	}

	@Test
	void 공개_상세_요청은_ACTIVE_전체_목록을_읽지_않는다() {
		Room room = room(RoomStatus.RECRUITING);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(room));

		RoomDetailReadService.RoomDetailReadResult publicResult = roomDetailReadService.findRoomDetail(7L, null);

		assertFalse(publicResult.currentUserWaiting());
		assertFalse(publicResult.currentUserIsActiveParticipant());
		verifyNoInteractions(participationRepository, roomWaitlistRepository);

		clearInvocations(participationRepository, roomWaitlistRepository);
		when(participationRepository.findByRoomIdAndUserId(7L, 99L)).thenReturn(Optional.empty());
		when(roomWaitlistRepository.findWaitingRoomIdsByUserIdAndRoomIds(99L, List.of(7L)))
			.thenReturn(List.of());

		assertFalse(roomDetailReadService.findRoomDetail(7L, 99L).currentUserWaiting());
		verify(participationRepository).findByRoomIdAndUserId(7L, 99L);
		verify(participationRepository, never()).findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE);
		verify(roomWaitlistRepository).findWaitingRoomIdsByUserIdAndRoomIds(99L, List.of(7L));

		clearInvocations(participationRepository, roomWaitlistRepository);
		when(participationRepository.findByRoomIdAndUserId(7L, 100L)).thenReturn(Optional.empty());
		when(roomWaitlistRepository.findWaitingRoomIdsByUserIdAndRoomIds(100L, List.of(7L)))
			.thenReturn(List.of(7L));

		assertTrue(roomDetailReadService.findRoomDetail(7L, 100L).currentUserWaiting());
		verify(participationRepository, never()).findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE);

		Participation canceledParticipation = org.mockito.Mockito.mock(Participation.class);
		when(canceledParticipation.getStatus()).thenReturn(ParticipationStatus.CANCELED);
		clearInvocations(participationRepository, roomWaitlistRepository);
		when(participationRepository.findByRoomIdAndUserId(7L, 101L))
			.thenReturn(Optional.of(canceledParticipation));
		when(roomWaitlistRepository.findWaitingRoomIdsByUserIdAndRoomIds(101L, List.of(7L)))
			.thenReturn(List.of());

		RoomDetailReadService.RoomDetailReadResult canceledResult = roomDetailReadService.findRoomDetail(7L, 101L);

		assertFalse(canceledResult.currentUserWaiting());
		assertFalse(canceledResult.currentUserIsActiveParticipant());
		verify(participationRepository, never()).findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE);

		Room closedRoom = room(RoomStatus.CLOSED);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(closedRoom));
		clearInvocations(participationRepository, roomWaitlistRepository);
		assertFalse(roomDetailReadService.findRoomDetail(7L, null).currentUserWaiting());
		verifyNoInteractions(participationRepository, roomWaitlistRepository);

		when(participationRepository.findByRoomIdAndUserId(7L, 99L)).thenReturn(Optional.empty());
		when(roomWaitlistRepository.findWaitingRoomIdsByUserIdAndRoomIds(99L, List.of(7L))).thenReturn(List.of());
		when(participationRepository.findByRoomIdAndUserId(7L, 100L)).thenReturn(Optional.empty());
		when(roomWaitlistRepository.findWaitingRoomIdsByUserIdAndRoomIds(100L, List.of(7L))).thenReturn(List.of(7L));
		when(participationRepository.findByRoomIdAndUserId(7L, 101L)).thenReturn(Optional.of(canceledParticipation));
		when(roomWaitlistRepository.findWaitingRoomIdsByUserIdAndRoomIds(101L, List.of(7L))).thenReturn(List.of());

		assertFalse(roomDetailReadService.findRoomDetail(7L, 99L).currentUserWaiting());
		assertTrue(roomDetailReadService.findRoomDetail(7L, 100L).currentUserWaiting());
		assertFalse(roomDetailReadService.findRoomDetail(7L, 101L).currentUserWaiting());
		verify(participationRepository, never()).findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE);
	}

	@Test
	void 없는_방과_최종_상태_비관계자_요청은_ACTIVE_전체_목록을_읽지_않는다() {
		when(roomRepository.findById(404L)).thenReturn(Optional.empty());

		BusinessException missingRoom = assertThrows(
			BusinessException.class, () -> roomDetailReadService.findRoomDetail(404L, 99L));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, missingRoom.getErrorCode());
		verifyNoInteractions(participationRepository, roomWaitlistRepository);

		Room finalRoom = room(RoomStatus.CANCELED);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(finalRoom));
		when(participationRepository.findByRoomIdAndUserId(7L, 99L)).thenReturn(Optional.empty());

		RoomDetailReadService.RoomDetailReadResult result = roomDetailReadService.findRoomDetail(7L, 99L);

		assertEquals(List.of(), result.activeParticipations());
		assertFalse(result.currentUserWaiting());
		verify(participationRepository).findByRoomIdAndUserId(7L, 99L);
		verify(participationRepository, never()).findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE);
		verifyNoInteractions(roomWaitlistRepository);

		Room finishedRoom = room(RoomStatus.FINISHED);
		when(roomRepository.findById(8L)).thenReturn(Optional.of(finishedRoom));
		clearInvocations(participationRepository, roomWaitlistRepository);
		assertFalse(roomDetailReadService.findRoomDetail(8L, null).currentUserWaiting());
		when(participationRepository.findByRoomIdAndUserId(8L, 100L)).thenReturn(Optional.empty());
		assertFalse(roomDetailReadService.findRoomDetail(8L, 100L).currentUserWaiting());
		verify(participationRepository, never()).findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			8L, ParticipationStatus.ACTIVE);
	}

	@Test
	void 주최자는_단건_참가_관계_조회_없이_ACTIVE_목록을_읽고_ACTIVE_참가자만_전체_목록을_읽는다() {
		Room room = room(RoomStatus.RECRUITING);
		Participation activeParticipation = org.mockito.Mockito.mock(Participation.class);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(room));
		when(participationRepository.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE))
			.thenReturn(List.of(activeParticipation));

		RoomDetailReadService.RoomDetailReadResult hostResult = roomDetailReadService.findRoomDetail(7L, 42L);

		assertSame(room, hostResult.room());
		assertEquals(List.of(activeParticipation), hostResult.activeParticipations());
		assertFalse(hostResult.currentUserIsActiveParticipant());
		verify(participationRepository, never()).findByRoomIdAndUserId(7L, 42L);
		verify(participationRepository).findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE);

		Participation requesterParticipation = org.mockito.Mockito.mock(Participation.class);
		when(requesterParticipation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		clearInvocations(participationRepository, roomWaitlistRepository);
		when(participationRepository.findByRoomIdAndUserId(7L, 77L))
			.thenReturn(Optional.of(requesterParticipation));

		RoomDetailReadService.RoomDetailReadResult participantResult = roomDetailReadService.findRoomDetail(7L, 77L);

		assertEquals(List.of(activeParticipation), participantResult.activeParticipations());
		assertTrue(participantResult.currentUserIsActiveParticipant());
		verify(participationRepository).findByRoomIdAndUserId(7L, 77L);
		verify(participationRepository).findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE);
		verifyNoInteractions(roomWaitlistRepository);
	}

	private Room room(RoomStatus status) {
		Room room = org.mockito.Mockito.mock(Room.class);
		when(room.getHostUserId()).thenReturn(42L);
		lenient().when(room.getStatus()).thenReturn(status);
		return room;
	}
}
