package cloud.bamsongi.albammate.room.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.RoomActionAvailability;

/** 공개 방 목록과 상세에 공통으로 쓰는 비식별 응답이다. */
public record PublicRoomResponse(
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
	boolean waitlistable)
	implements
		RoomDetailResponse {

	public static PublicRoomResponse from(
		Room room, GameSummary game, RoomActionAvailability availability) {
		return new PublicRoomResponse(
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
			availability.waitlistable());
	}
}
