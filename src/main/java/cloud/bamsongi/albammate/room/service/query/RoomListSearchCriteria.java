package cloud.bamsongi.albammate.room.service.query;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import cloud.bamsongi.albammate.room.dto.RoomListRequest;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

/** 공개 방 목록의 동적 조회 조건을 불변으로 전달한다. */
record RoomListSearchCriteria(
	RoomType roomType,
	RoomStatus status,
	Long gameId,
	String keyword,
	Instant startsAtFrom,
	Instant startsAtTo,
	Integer minRemainingSeats,
	Set<ExperienceLevel> experienceLevels,
	boolean rulemasterOnly) {

	RoomListSearchCriteria {
		experienceLevels = Set.copyOf(experienceLevels);
	}

	static RoomListSearchCriteria from(RoomListRequest request, String keyword) {
		return new RoomListSearchCriteria(
			request.getType(),
			request.getStatus(),
			request.getGameId(),
			keyword,
			request.getStartsAtFrom(),
			request.getStartsAtTo(),
			request.getMinRemainingSeats(),
			request.getExperienceLevels(),
			request.isRulemasterOnly());
	}

	boolean hasKeyword() {
		return keyword != null;
	}

	String keywordOrEmpty() {
		return hasKeyword() ? keyword : "";
	}

	boolean hasStartsAtFrom() {
		return startsAtFrom != null;
	}

	Instant startsAtFromOrEpoch() {
		return hasStartsAtFrom() ? startsAtFrom : Instant.EPOCH;
	}

	boolean hasStartsAtTo() {
		return startsAtTo != null;
	}

	Instant startsAtToOrEpoch() {
		return hasStartsAtTo() ? startsAtTo : Instant.EPOCH;
	}

	boolean hasMinRemainingSeats() {
		return minRemainingSeats != null;
	}

	int minRemainingSeatsOrZero() {
		return hasMinRemainingSeats() ? minRemainingSeats : 0;
	}

	Set<ExperienceLevel> appliedExperienceLevels() {
		return experienceLevels.isEmpty() ? EnumSet.allOf(ExperienceLevel.class) : experienceLevels;
	}
}
