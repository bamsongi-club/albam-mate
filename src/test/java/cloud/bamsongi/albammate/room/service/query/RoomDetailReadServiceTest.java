package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistStateProjection;

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
	void 방과_joinedAt_ID_순서의_ACTIVE_참가관계를_함께_읽는다() {
		Room room = org.mockito.Mockito.mock(Room.class);
		Participation first = org.mockito.Mockito.mock(Participation.class);
		Participation second = org.mockito.Mockito.mock(Participation.class);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(room));
		when(participationRepository.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE))
			.thenReturn(List.of(first, second));

		RoomDetailReadService.RoomDetailReadResult result = roomDetailReadService.findRoomDetail(7L, null);

		assertSame(room, result.room());
		assertEquals(List.of(first, second), result.activeParticipations());
		verify(roomRepository).findById(7L);
		verify(participationRepository)
			.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(7L, ParticipationStatus.ACTIVE);
	}

	@Test
	void T6_주최자와_ACTIVE_참가자는_이미_읽은_사실로_대기열_순번_조회를_건너뛴다() {
		Room room = org.mockito.Mockito.mock(Room.class);
		Participation activeParticipation = org.mockito.Mockito.mock(Participation.class);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(room));
		when(room.getHostUserId()).thenReturn(42L);
		when(participationRepository.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE))
			.thenReturn(List.of(activeParticipation));
		when(activeParticipation.getUserId()).thenReturn(77L);

		assertFalse(roomDetailReadService.findRoomDetail(7L, 42L).currentUserWaiting());
		assertFalse(roomDetailReadService.findRoomDetail(7L, 77L).currentUserWaiting());

		verify(roomWaitlistRepository, never()).findStateWithPositionByRoomIdAndUserId(
			org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	void T5_관계없는_요청자의_현재_WAITING_사실을_상세_결과로_전달한다() {
		Room room = org.mockito.Mockito.mock(Room.class);
		RoomWaitlistStateProjection waitingState = org.mockito.Mockito.mock(RoomWaitlistStateProjection.class);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(room));
		when(room.getHostUserId()).thenReturn(42L);
		when(participationRepository.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE))
			.thenReturn(List.of());
		when(roomWaitlistRepository.findStateWithPositionByRoomIdAndUserId(7L, 99L))
			.thenReturn(Optional.of(waitingState));
		when(waitingState.getStatus()).thenReturn(RoomWaitlistStatus.WAITING);

		assertTrue(roomDetailReadService.findRoomDetail(7L, 99L).currentUserWaiting());
	}

	@Test
	void T6_상세_스냅샷_결과는_현재_운영_시그니처만_노출한다() {
		assertEquals(
			0,
			java.util.Arrays.stream(RoomDetailReadService.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("findRoomDetail") && method.getParameterCount() == 1)
				.count());
		assertEquals(
			0,
			java.util.Arrays.stream(RoomDetailReadService.RoomDetailReadResult.class.getDeclaredConstructors())
				.filter(constructor -> constructor.getParameterCount() == 2)
				.count());
	}
}
