package cloud.bamsongi.albammate.room.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

class RoomStatusCorrectionTest {

	private static final Instant START_AT = Instant.parse("2026-07-27T00:00:00Z");

	@Test
	void 시작_경계_직전에는_모집중이고_정확히_도달하면_닫힌다() {
		Room room = room(START_AT);

		assertFalse(room.reconcileStateAt(START_AT.minusNanos(1)));
		assertEquals(RoomStatus.RECRUITING, room.getStatus());

		assertTrue(room.reconcileStateAt(START_AT));
		assertEquals(RoomStatus.CLOSED, room.getStatus());
	}

	@Test
	void 자동_종료_경계_직전에는_닫힘이고_정확히_도달하면_종료된다() {
		Room room = room(START_AT);
		room.reconcileStateAt(START_AT);
		Instant automaticFinishAt = START_AT.plus(Room.AUTOMATIC_FINISH_AFTER_START);

		assertFalse(room.reconcileStateAt(automaticFinishAt.minusNanos(1)));
		assertEquals(RoomStatus.CLOSED, room.getStatus());

		assertTrue(room.reconcileStateAt(automaticFinishAt));
		assertEquals(RoomStatus.FINISHED, room.getStatus());
	}

	@Test
	void 시작과_자동_종료_경계를_지난_모집중_방은_한_번에_종료까지_전이한다() {
		Room room = room(START_AT);
		Instant afterAutomaticFinish = START_AT.plus(Room.AUTOMATIC_FINISH_AFTER_START).plusNanos(1);

		assertTrue(room.reconcileStateAt(afterAutomaticFinish));
		assertEquals(RoomStatus.FINISHED, room.getStatus());
	}

	@Test
	void 취소와_종료_상태는_시간_보정으로_되돌아가지_않는다() {
		Room canceledRoom = room(START_AT);
		assertTrue(canceledRoom.cancel());
		Room finishedRoom = room(START_AT);
		assertTrue(finishedRoom.reconcileStateAt(START_AT.plus(Room.AUTOMATIC_FINISH_AFTER_START)));
		Instant correctionTime = START_AT.plus(Room.AUTOMATIC_FINISH_AFTER_START).plusNanos(1);

		assertFalse(canceledRoom.reconcileStateAt(correctionTime));
		assertFalse(finishedRoom.reconcileStateAt(correctionTime));
		assertEquals(RoomStatus.CANCELED, canceledRoom.getStatus());
		assertEquals(RoomStatus.FINISHED, finishedRoom.getStatus());
	}

	private Room room(Instant startAt) {
		return Room.create(
			1L,
			RoomType.PERSON_FOCUSED,
			"상태 보정 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startAt,
			"홍대 카페",
			3);
	}

}
