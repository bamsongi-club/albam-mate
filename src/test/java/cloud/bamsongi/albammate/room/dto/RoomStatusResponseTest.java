package cloud.bamsongi.albammate.room.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;

class RoomStatusResponseTest {

	@Test
	void 방의_식별자와_현재_상태로_상태_응답을_구성한다() {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(7L);
		when(room.getStatus()).thenReturn(RoomStatus.FINISHED);

		RoomStatusResponse response = RoomStatusResponse.from(room);

		assertEquals(7L, response.roomId());
		assertEquals(RoomStatus.FINISHED, response.roomStatus());
	}
}
