package cloud.bamsongi.albammate.room.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.RoomActionAvailability;

class ParticipantRoomResponseTest {

	@Test
	void 방과_참가자_정보로_관계자_상세_응답을_구성한다() {
		Room room = room();
		NicknameSummary host = new NicknameSummary("방장", "https://cdn.example.com/host.png");
		GameSummary game = new GameSummary(3L, 1003L, "카탄");

		ParticipantRoomResponse response = ParticipantRoomResponse.from(
			room, game, new RoomActionAvailability(true, false), MyRole.JOINED, host,
			List.of(host, new NicknameSummary("참가자", null)));

		assertEquals(7L, response.id());
		assertEquals(game, response.game());
		assertEquals(3, response.participantCount());
		assertEquals(2, response.remainingRecruitmentSeats());
		assertEquals(true, response.joinable());
		assertEquals(MyRole.JOINED, response.myRole());
		assertEquals("장소", response.place());
		assertEquals(List.of(host, new NicknameSummary("참가자", null)), response.participants());
	}

	private Room room() {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(7L);
		when(room.getRoomType()).thenReturn(RoomType.GAME_FOCUSED);
		when(room.getTitle()).thenReturn("제목");
		when(room.getDescription()).thenReturn("소개");
		when(room.getExperienceLevel()).thenReturn(ExperienceLevel.ALL_LEVELS);
		when(room.isRulemasterLed()).thenReturn(true);
		when(room.getStartAt()).thenReturn(Instant.parse("2026-08-01T00:00:00Z"));
		when(room.getRegion()).thenReturn("홍대");
		when(room.getCapacity()).thenReturn(4);
		when(room.getTotalParticipantCount()).thenReturn(3);
		when(room.getRemainingRecruitmentSeats()).thenReturn(2);
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(room.getPlace()).thenReturn("장소");
		return room;
	}
}
