package cloud.bamsongi.albammate.room.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;

class RoomWaitlistTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-04T00:00:00Z");

	@Test
	void 신규_대기_행은_Persistable_신규로_판정한다() {
		RoomWaitlist waitlist = RoomWaitlist.create(10L, 20L, 1L, REQUEST_TIME);

		assertTrue(waitlist.isNew());
		assertEquals(RoomWaitlistStatus.WAITING, waitlist.getStatus());
		assertEquals(1L, waitlist.getQueueOrder());
		assertEquals(REQUEST_TIME, waitlist.getQueuedAt());
		assertEquals(REQUEST_TIME, waitlist.getCreatedAt());
		assertEquals(REQUEST_TIME, waitlist.getUpdatedAt());
	}

}
