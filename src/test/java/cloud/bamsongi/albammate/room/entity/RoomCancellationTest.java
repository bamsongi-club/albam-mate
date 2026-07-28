package cloud.bamsongi.albammate.room.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RoomCancellationTest {

    @Test
    void 시작_전_마지막_참가자가_취소하면_닫힌_방이_모집중으로_복귀한다() {
        Room room = room(1);
        room.addActiveParticipant();

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
    void 최종_상태는_참가_취소로_모집중으로_바뀌지_않는다() throws ReflectiveOperationException {
        for (RoomStatus finalStatus : new RoomStatus[] {RoomStatus.CANCELED, RoomStatus.FINISHED}) {
            Room room = room(2);
            room.addActiveParticipant();
            setStatus(room, finalStatus);

            room.removeActiveParticipant();

            assertEquals(0, room.getActiveParticipantCount());
            assertEquals(finalStatus, room.getStatus());
        }
    }

    private Room room(int capacity) {
        return Room.create(
                1L,
                RoomType.PERSON_FOCUSED,
                "취소 방",
                null,
                null,
                ExperienceLevel.ALL_LEVELS,
                false,
                Instant.parse("2026-07-29T00:00:00Z"),
                "홍대",
                capacity);
    }

    private void setStatus(Room room, RoomStatus status) throws ReflectiveOperationException {
        Field field = Room.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(room, status);
    }
}
