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
	boolean chatAvailable,
	String lastMessagePreview,
	Instant lastMessageAt,
	int unreadCount) {

	public static MyRoomListItem from(
		Room room,
		GameSummary game,
		RoomActionAvailability availability,
		MyRole myRole,
		ParticipationStatus participationStatus) {
		return from(room, room.getStatus(), game, availability, myRole, participationStatus, null, null, 0);
	}

	public static MyRoomListItem from(
		Room room,
		RoomStatus effectiveStatus,
		GameSummary game,
		RoomActionAvailability availability,
		MyRole myRole,
		ParticipationStatus participationStatus) {
		return from(room, effectiveStatus, game, availability, myRole, participationStatus, null, null, 0);
	}

	/** CHAT-07 채팅 목록 마지막 메시지·미읽음 상태를 더해 항목을 조립한다. */
	public static MyRoomListItem from(
		Room room,
		RoomStatus effectiveStatus,
		GameSummary game,
		RoomActionAvailability availability,
		MyRole myRole,
		ParticipationStatus participationStatus,
		String lastMessagePreview,
		Instant lastMessageAt,
		int unreadCount) {
		boolean chatAvailable = effectiveStatus.isChatAvailable();
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
			effectiveStatus,
			availability.joinable(),
			availability.waitlistable(),
			myRole,
			participationStatus,
			chatAvailable,
			chatAvailable ? lastMessagePreview : null,
			chatAvailable ? lastMessageAt : null,
			chatAvailable ? unreadCount : 0);
	}
}
