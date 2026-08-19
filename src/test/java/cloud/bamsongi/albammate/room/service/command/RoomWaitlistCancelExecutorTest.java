package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistStateProjection;

class RoomWaitlistCancelExecutorTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-05T00:00:00Z");

	@Test
	void T2_취소는_고정된_요청시각으로_ROOM을_보정한_뒤_조회한_순번의_WAITING만_조건부_전이한다() {
		RoomRepository roomRepository = Mockito.mock(RoomRepository.class);
		RoomWaitlistRepository roomWaitlistRepository = Mockito.mock(RoomWaitlistRepository.class);
		RoomWaitlistStateProjection waiting = Mockito.mock(RoomWaitlistStateProjection.class);
		Room room = Room.create(1L, RoomType.PERSON_FOCUSED, "대기 취소", null, null,
			ExperienceLevel.ALL_LEVELS, false, REQUEST_TIME.minusSeconds(1), "홍대", 2);
		when(roomRepository.findByIdForWrite(10L)).thenReturn(Optional.of(room));
		when(roomWaitlistRepository.findStateWithPositionByRoomIdAndUserId(10L, 7L))
			.thenReturn(Optional.of(waiting));
		when(waiting.getStatus()).thenReturn(RoomWaitlistStatus.WAITING);
		when(waiting.getQueueOrder()).thenReturn(20L);
		when(roomWaitlistRepository.cancelWaiting(eq(10L), eq(7L), eq(20L), eq(REQUEST_TIME)))
			.thenAnswer(invocation -> {
				assertEquals(RoomStatus.CLOSED, room.getStatus());
				return 1;
			});

		new RoomWaitlistCancelExecutor(roomRepository, roomWaitlistRepository, new MockEnvironment())
			.cancel(7L, 10L, REQUEST_TIME);
	}

	@Test
	void C_T2_대기_취소는_관련_변경보다_먼저_timeout을_설정하고_ROOM_쓰기_잠금을_획득한다() {
		RoomRepository roomRepository = Mockito.mock(RoomRepository.class);
		RoomWaitlistRepository roomWaitlistRepository = Mockito.mock(RoomWaitlistRepository.class);
		RoomWaitlistStateProjection waiting = Mockito.mock(RoomWaitlistStateProjection.class);
		Room room = Room.create(1L, RoomType.PERSON_FOCUSED, "잠금 대기 취소", null, null,
			ExperienceLevel.ALL_LEVELS, false, REQUEST_TIME.plusSeconds(60), "홍대", 2);
		when(roomRepository.findByIdForWrite(10L)).thenReturn(Optional.of(room));
		when(roomWaitlistRepository.findStateWithPositionByRoomIdAndUserId(10L, 7L))
			.thenReturn(Optional.of(waiting));
		when(waiting.getStatus()).thenReturn(RoomWaitlistStatus.WAITING);
		when(waiting.getQueueOrder()).thenReturn(20L);
		when(roomWaitlistRepository.cancelWaiting(10L, 7L, 20L, REQUEST_TIME)).thenReturn(1);

		new RoomWaitlistCancelExecutor(roomRepository, roomWaitlistRepository, new MockEnvironment())
			.cancel(7L, 10L, REQUEST_TIME);

		InOrder order = inOrder(roomRepository, roomWaitlistRepository);
		order.verify(roomRepository).setLocalWriteLockTimeout();
		order.verify(roomRepository).findByIdForWrite(10L);
		order.verify(roomWaitlistRepository).findStateWithPositionByRoomIdAndUserId(10L, 7L);
	}
}
