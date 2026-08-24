package cloud.bamsongi.albammate.room.enums;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoomStatusTest {

	@Test
	void 모집중과_모집마감_상태에서만_채팅이_가능하다() {
		assertTrue(RoomStatus.RECRUITING.isChatAvailable());
		assertTrue(RoomStatus.CLOSED.isChatAvailable());
		assertFalse(RoomStatus.CANCELED.isChatAvailable());
		assertFalse(RoomStatus.FINISHED.isChatAvailable());
	}
}
