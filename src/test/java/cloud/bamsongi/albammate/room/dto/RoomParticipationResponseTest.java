package cloud.bamsongi.albammate.room.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;

class RoomParticipationResponseTest {

	@Test
	void 방과_참가_상태로_참가_응답을_구성한다() {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(7L);
		when(room.getStatus()).thenReturn(RoomStatus.CLOSED);
		when(room.getActiveParticipantCount()).thenReturn(3);
		when(room.getCapacity()).thenReturn(3);
		when(room.getTotalParticipantCount()).thenReturn(4);
		when(room.getRemainingRecruitmentSeats()).thenReturn(0);

		RoomParticipationResponse response = RoomParticipationResponse.from(room, ParticipationStatus.ACTIVE);

		assertEquals(7L, response.roomId());
		assertEquals(ParticipationStatus.ACTIVE, response.participationStatus());
		assertEquals(RoomStatus.CLOSED, response.roomStatus());
		assertEquals(4, response.participantCount());
		assertEquals(0, response.remainingRecruitmentSeats());
	}
}
