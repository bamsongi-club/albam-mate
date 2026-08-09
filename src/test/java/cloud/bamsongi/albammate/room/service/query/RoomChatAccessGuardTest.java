package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;

class RoomChatAccessGuardTest {

	private final RoomRepository roomRepository = mock(RoomRepository.class);
	private final ParticipationRepository participationRepository = mock(ParticipationRepository.class);
	private final RoomStatusCorrectionCoordinator statusCorrectionCoordinator = mock(
		RoomStatusCorrectionCoordinator.class);
	private final RoomChatAccessGuard guard = new RoomChatAccessGuard(
		roomRepository,
		participationRepository,
		statusCorrectionCoordinator,
		Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC));

	@Test
	void T3_경량_검증은_보정하지_않고_현재_주최자_또는_ACTIVE_참가자만_허용한다() throws Exception {
		Room room = mock(Room.class);
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(room.getHostUserId()).thenReturn(1L);
		when(roomRepository.findByIdForChatAccess(7L)).thenReturn(Optional.of(room));
		Participation participation = mock(Participation.class);
		when(participation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(participationRepository.findByRoomIdAndUserId(7L, 2L)).thenReturn(Optional.of(participation));

		assertEquals("strong", guard.executeWithAccess(1L, 7L, () -> "strong"));
		verify(statusCorrectionCoordinator).correctRoom(eq(7L), any(Instant.class));
		clearInvocations(roomRepository, statusCorrectionCoordinator);

		guard.verifyCurrentAccess(1L, 7L);
		guard.verifyCurrentAccess(2L, 7L);

		Transactional transaction = RoomChatAccessGuard.class
			.getDeclaredMethod("verifyCurrentAccess", long.class, long.class)
			.getAnnotation(Transactional.class);
		assertFalse(transaction.readOnly());
		verify(roomRepository, times(2)).findByIdForChatAccess(7L);
		verify(roomRepository, never()).findById(7L);
		verifyNoInteractions(statusCorrectionCoordinator);
	}

	@Test
	void T2_경량_검증은_취소된_참가자와_채팅_불가_방을_거절한다() {
		Room recruitingRoom = mock(Room.class);
		when(recruitingRoom.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(recruitingRoom.getHostUserId()).thenReturn(1L);
		when(roomRepository.findByIdForChatAccess(7L)).thenReturn(Optional.of(recruitingRoom));
		Participation canceledParticipation = mock(Participation.class);
		when(canceledParticipation.getStatus()).thenReturn(ParticipationStatus.CANCELED);
		when(participationRepository.findByRoomIdAndUserId(7L, 2L)).thenReturn(Optional.of(canceledParticipation));

		Room canceledRoom = mock(Room.class);
		when(canceledRoom.getStatus()).thenReturn(RoomStatus.CANCELED);
		when(canceledRoom.getHostUserId()).thenReturn(1L);
		when(roomRepository.findByIdForChatAccess(8L)).thenReturn(Optional.of(canceledRoom));

		assertForbidden(() -> guard.verifyCurrentAccess(2L, 7L));
		assertForbidden(() -> guard.verifyCurrentAccess(1L, 8L));
	}

	private void assertForbidden(Runnable action) {
		BusinessException exception = assertThrows(BusinessException.class, action::run);
		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}
}
