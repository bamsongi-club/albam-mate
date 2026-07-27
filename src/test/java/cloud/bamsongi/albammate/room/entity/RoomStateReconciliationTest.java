package cloud.bamsongi.albammate.room.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RoomStateReconciliationTest {

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

        assertFalse(room.reconcileStateAt(START_AT.plusSeconds(24 * 60 * 60).minusNanos(1)));
        assertEquals(RoomStatus.CLOSED, room.getStatus());

        assertTrue(room.reconcileStateAt(START_AT.plusSeconds(24 * 60 * 60)));
        assertEquals(RoomStatus.FINISHED, room.getStatus());
    }

    @Test
    void 오래_지난_모집중_방은_한_번에_종료까지_전이한다() {
        Room room = room(START_AT);

        assertTrue(room.reconcileStateAt(START_AT.plusSeconds(24 * 60 * 60)));
        assertEquals(RoomStatus.FINISHED, room.getStatus());
    }

    @Test
    void 취소와_종료_상태는_보정하지_않는다() throws ReflectiveOperationException {
        Room canceledRoom = room(START_AT);
        setStatus(canceledRoom, RoomStatus.CANCELED);
        Room finishedRoom = room(START_AT);
        setStatus(finishedRoom, RoomStatus.FINISHED);

        assertFalse(canceledRoom.reconcileStateAt(START_AT.plusSeconds(48 * 60 * 60)));
        assertFalse(finishedRoom.reconcileStateAt(START_AT.plusSeconds(48 * 60 * 60)));
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

    private void setStatus(Room room, RoomStatus status) throws ReflectiveOperationException {
        Field field = Room.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(room, status);
    }
}
