package cloud.bamsongi.albammate.chat.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChatRoomPersistenceModelTest {

	@Test
	void 새_채팅방은_ROOM_식별자만_저장하고_보관_시각은_비워둔다() {
		ChatRoom chatRoom = ChatRoom.create(42L);

		assertNull(chatRoom.getId());
		assertEquals(42L, chatRoom.getRoomId());
		assertNull(chatRoom.getPurgeAfter());
		assertNull(chatRoom.getMessagesPurgedAt());
	}
}
