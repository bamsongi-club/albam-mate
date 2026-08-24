package cloud.bamsongi.albammate.room.dto;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;

public record RoomParticipationResponse(
	Long roomId,
	ParticipationStatus participationStatus,
	RoomStatus roomStatus,
	int participantCount,
	int remainingRecruitmentSeats) {

	public static RoomParticipationResponse from(Room room, ParticipationStatus participationStatus) {
		return new RoomParticipationResponse(
			room.getId(),
			participationStatus,
			room.getStatus(),
			room.getTotalParticipantCount(),
			room.getRemainingRecruitmentSeats());
	}
}
