package cloud.bamsongi.albammate.room.dto;

import java.time.Instant;
import java.util.List;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.RoomActionAvailability;

/** 주최자 또는 현재 활성 참가자에게 반환하는 방 상세 표현이다. */
public record ParticipantRoomResponse(
	Long id,
	RoomType roomType,
	String title,
	String description,
	GameSummary game,
	ExperienceLevel experienceLevel,
	boolean isRulemasterLed,
	Instant startsAt,
	String region,
	int recruitmentCapacity,
	int participantCount,
	int remainingRecruitmentSeats,
	RoomStatus status,
	boolean joinable,
	boolean waitlistable,
	MyRole myRole,
	String place,
	NicknameSummary host,
	List<NicknameSummary> participants)
	implements
		RoomDetailResponse {

	public static ParticipantRoomResponse from(
		Room room,
		GameSummary game,
		RoomActionAvailability availability,
		MyRole myRole,
		NicknameSummary host,
		List<NicknameSummary> participants) {
		return new ParticipantRoomResponse(
			room.getId(),
			room.getRoomType(),
			room.getTitle(),
			room.getDescription(),
			game,
			room.getExperienceLevel(),
			room.isRulemasterLed(),
			room.getStartAt(),
			room.getRegion(),
			room.getCapacity(),
			room.getTotalParticipantCount(),
			room.getRemainingRecruitmentSeats(),
			room.getStatus(),
			availability.joinable(),
			availability.waitlistable(),
			myRole,
			room.getPlace(),
			host,
			participants);
	}
}
