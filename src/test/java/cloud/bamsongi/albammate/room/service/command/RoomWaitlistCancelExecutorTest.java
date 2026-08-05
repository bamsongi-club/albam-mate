package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

class RoomWaitlistCancelExecutorTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-05T00:00:00Z");

	@Test
	void T7_취소는_고정된_요청시각으로_ROOM을_보정한_뒤_WAITING만_조건부_전이한다() {
		RoomRepository roomRepository = Mockito.mock(RoomRepository.class);
		RoomWaitlistRepository roomWaitlistRepository = Mockito.mock(RoomWaitlistRepository.class);
		Room room = Room.create(1L, RoomType.PERSON_FOCUSED, "대기 취소", null, null,
			ExperienceLevel.ALL_LEVELS, false, REQUEST_TIME.minusSeconds(1), "홍대", 2);
		when(roomRepository.findById(10L)).thenReturn(Optional.of(room));
		when(roomWaitlistRepository.cancelWaiting(eq(10L), eq(7L), eq(REQUEST_TIME))).thenAnswer(invocation -> {
			assertEquals(RoomStatus.CLOSED, room.getStatus());
			return 1;
		});

		new RoomWaitlistCancelExecutor(roomRepository, roomWaitlistRepository).cancel(7L, 10L, REQUEST_TIME);
	}
}
