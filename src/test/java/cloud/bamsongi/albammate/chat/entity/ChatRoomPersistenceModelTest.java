package cloud.bamsongi.albammate.chat.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;

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

	@Test
	void 최종_상태_전환_시점에서_30일_뒤_보관_기한을_한번만_정한다() {
		ChatRoom chatRoom = ChatRoom.create(42L);

		chatRoom.schedulePurgeAfter(Instant.parse("2026-08-03T00:00:00Z"));
		chatRoom.schedulePurgeAfter(Instant.parse("2026-08-04T00:00:00Z"));

		assertEquals(Instant.parse("2026-09-02T00:00:00Z"), chatRoom.getPurgeAfter());
	}
}
