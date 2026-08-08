package cloud.bamsongi.albammate.room.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.RoomActionAvailability;

/** 내 모임 목록에만 현재 사용자의 역할과 참가 상태를 추가한 비식별 방 표현이다. */
public record MyRoomListItem(
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
	ParticipationStatus participationStatus,
	boolean chatAvailable) {

	public static MyRoomListItem from(
		Room room,
		GameSummary game,
		RoomActionAvailability availability,
		MyRole myRole,
		ParticipationStatus participationStatus) {
		return new MyRoomListItem(
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
			participationStatus,
			room.getStatus().isChatAvailable());
	}
}
