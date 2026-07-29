package cloud.bamsongi.albammate.room.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

class PublicRoomResponseTest {

	@Test
	void 방과_판단된_참가_가능_여부로_공개_응답을_구성한다() {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(7L);
		when(room.getRoomType()).thenReturn(RoomType.PERSON_FOCUSED);
		when(room.getTitle()).thenReturn("제목");
		when(room.getDescription()).thenReturn("소개");
		when(room.getExperienceLevel()).thenReturn(ExperienceLevel.ALL_LEVELS);
		when(room.isRulemasterLed()).thenReturn(false);
		when(room.getStartAt()).thenReturn(Instant.parse("2026-08-01T00:00:00Z"));
		when(room.getRegion()).thenReturn("홍대");
		when(room.getCapacity()).thenReturn(3);
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);

		PublicRoomResponse response = PublicRoomResponse.from(
			room, new GameSummary(3L, 1003L, "카탄"), 1, true);

		assertEquals(7L, response.id());
		assertEquals(2, response.participantCount());
		assertEquals(2, response.remainingRecruitmentSeats());
		assertEquals(true, response.joinable());
		assertEquals(RoomStatus.RECRUITING, response.status());
	}
}
