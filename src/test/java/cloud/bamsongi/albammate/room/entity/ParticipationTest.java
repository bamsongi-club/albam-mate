package cloud.bamsongi.albammate.room.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ParticipationTest {

    @Test
    void 활성_참가_관계를_취소하면_취소_시각을_보존한다() {
        Participation participation =
                Participation.createActive(room(), 2L, Instant.parse("2026-07-28T00:00:00Z"));
        Instant canceledAt = Instant.parse("2026-07-28T00:30:00Z");

        participation.cancel(canceledAt);

        assertEquals(ParticipationStatus.CANCELED, participation.getStatus());
        assertEquals(canceledAt, participation.getCanceledAt());
    }

    private Room room() {
        return Room.create(
                1L,
                RoomType.PERSON_FOCUSED,
                "참가 관계",
                null,
                null,
                ExperienceLevel.ALL_LEVELS,
                false,
                Instant.parse("2026-07-29T00:00:00Z"),
                "홍대",
                1);
    }
}
