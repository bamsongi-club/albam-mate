package cloud.bamsongi.albammate.room.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

class RoomStatusTransitionTest {

	private static final Instant START_AT = Instant.parse("2026-07-28T00:00:00Z");

	@Test
	void 방을_생성하면_모집중_상태가_된다() {
		Room room = room(START_AT.plusSeconds(3600));

		assertEquals(RoomStatus.RECRUITING, room.getStatus());
	}

	@Test
	void 모집중과_마감_방만_취소할_수_있다() {
		Room recruitingRoom = room(START_AT.plusSeconds(3600));
		Room closedRoom = room(START_AT);
		closedRoom.reconcileStateAt(START_AT);

		assertTrue(recruitingRoom.cancel());
		assertTrue(closedRoom.cancel());
		assertEquals(RoomStatus.CANCELED, recruitingRoom.getStatus());
		assertEquals(RoomStatus.CANCELED, closedRoom.getStatus());
	}

	@Test
	void 시작_시각_이후_마감된_방만_종료할_수_있고_최종_상태는_유지된다() {
		Room room = room(START_AT);
		room.reconcileStateAt(START_AT);

		assertTrue(room.finishAt(START_AT));
		assertFalse(room.cancel());
		assertFalse(room.finishAt(START_AT));
		assertEquals(RoomStatus.FINISHED, room.getStatus());
	}

	@Test
	void 시작_전_마감_방은_종료할_수_없다() {
		Room room = room(START_AT.plusSeconds(1));
		room.reconcileStateAt(START_AT.plusSeconds(1));

		assertFalse(room.finishAt(START_AT));
		assertEquals(RoomStatus.CLOSED, room.getStatus());
	}

	@Test
	void 취소된_방은_모든_시간_경계_이후에도_최종_상태를_유지한다() {
		Room room = room(START_AT);
		assertTrue(room.cancel());

		assertFalse(room.reconcileStateAt(START_AT));
		assertFalse(room.reconcileStateAt(START_AT.plus(Room.AUTOMATIC_FINISH_AFTER_START)));
		assertEquals(RoomStatus.CANCELED, room.getStatus());
	}

	private Room room(Instant startsAt) {
		return Room.create(
			42L,
			RoomType.PERSON_FOCUSED,
			"방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startsAt,
			"장소",
			3);
	}
}
