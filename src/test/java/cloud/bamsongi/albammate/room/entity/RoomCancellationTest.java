package cloud.bamsongi.albammate.room.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

class RoomCancellationTest {

	private static final Instant START_AT = Instant.parse("2026-07-29T00:00:00Z");

	@Test
	void 정원_충족_후_시작_전_활성_참가자가_취소하면_모집중으로_복귀한다() {
		Room room = room(1, START_AT.plusSeconds(1));

		assertFalse(room.reconcileStateAt(START_AT));
		assertEquals(RoomStatus.RECRUITING, room.getStatus());

		room.addActiveParticipant();
		assertEquals(RoomStatus.CLOSED, room.getStatus());

		room.removeActiveParticipant();

		assertEquals(0, room.getActiveParticipantCount());
		assertEquals(RoomStatus.RECRUITING, room.getStatus());
	}

	@Test
	void 정원이_가득찬_방에_참가자를_더하면_예외가_발생한다() {
		Room room = room(1);
		room.addActiveParticipant();

		assertThrows(IllegalStateException.class, room::addActiveParticipant);
	}

	@Test
	void 활성_참가자가_없는_방을_취소하면_예외가_발생한다() {
		Room room = room(1);

		assertThrows(IllegalStateException.class, room::removeActiveParticipant);
	}

	@Test
	void 최종_상태는_활성_참가_취소로_모집중으로_바뀌지_않는다() {
		Room canceledRoom = room(2);
		canceledRoom.addActiveParticipant();
		assertTrue(canceledRoom.cancel());

		Room finishedRoom = room(2);
		finishedRoom.addActiveParticipant();
		assertTrue(finishedRoom.reconcileStateAt(START_AT.plus(Room.AUTOMATIC_FINISH_AFTER_START)));

		canceledRoom.removeActiveParticipant();
		finishedRoom.removeActiveParticipant();

		assertEquals(0, canceledRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.CANCELED, canceledRoom.getStatus());
		assertEquals(0, finishedRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.FINISHED, finishedRoom.getStatus());
	}

	private Room room(int capacity) {
		return room(capacity, START_AT);
	}

	private Room room(int capacity, Instant startAt) {
		return Room.create(
			1L,
			RoomType.PERSON_FOCUSED,
			"취소 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startAt,
			"홍대",
			capacity);
	}
}
