package cloud.bamsongi.albammate.room.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

class MyRoomListItemTest {

	@Test
	void 방과_현재_사용자_관계로_내_모임_항목을_구성한다() {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(7L);
		when(room.getRoomType()).thenReturn(RoomType.PERSON_FOCUSED);
		when(room.getTitle()).thenReturn("제목");
		when(room.getDescription()).thenReturn("소개");
		when(room.getExperienceLevel()).thenReturn(ExperienceLevel.ALL_LEVELS);
		when(room.isRulemasterLed()).thenReturn(false);
		when(room.getStartAt()).thenReturn(Instant.parse("2026-08-01T00:00:00Z"));
		when(room.getRegion()).thenReturn("홍대");
		when(room.getCapacity()).thenReturn(4);
		when(room.getActiveParticipantCount()).thenReturn(2);
		when(room.getStatus()).thenReturn(RoomStatus.CLOSED);

		MyRoomListItem response = MyRoomListItem.from(
			room,
			new GameSummary(3L, 1003L, "카탄"),
			false,
			MyRole.JOINED,
			ParticipationStatus.ACTIVE);

		assertEquals(3, response.participantCount());
		assertEquals(2, response.remainingRecruitmentSeats());
		assertEquals(MyRole.JOINED, response.myRole());
		assertEquals(ParticipationStatus.ACTIVE, response.participationStatus());
	}
}
