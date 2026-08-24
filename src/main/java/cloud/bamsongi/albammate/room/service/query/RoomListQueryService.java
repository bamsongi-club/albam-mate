package cloud.bamsongi.albammate.room.service.query;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomListRequest;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.RoomActionAvailability;
import cloud.bamsongi.albammate.room.service.RoomActionAvailabilityEvaluator;
import cloud.bamsongi.albammate.room.service.RoomActionAvailabilityFacts;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RoomListQueryService {

	@NonNull private final RoomListReadService roomListReadService;
	@NonNull private final GameQuery gameQuery;
	@NonNull private final Clock clock;
	@NonNull private final RoomActionAvailabilityEvaluator roomActionAvailabilityEvaluator;

	/** 고정 요청 시각의 유효 상태로 공개 방을 고정 정렬과 요청자 기준 참가 가능 여부로 반환한다. */
	public PageResponse<PublicRoomResponse> findPage(
		RoomListRequest request, Optional<Long> currentUserId) {
		return findPage(RoomListSearchCriteria.from(request, normalizeKeyword(request.getKeyword())),
			request.getPage(), request.getSize(), currentUserId);
	}

	public PageResponse<PublicRoomResponse> findPage(
		RoomType roomType, Long gameId, String keyword, int page, int size, Optional<Long> currentUserId) {
		return findPage(new RoomListSearchCriteria(roomType, null, gameId, normalizeKeyword(keyword), null, null,
			null, Set.of(), false), page, size, currentUserId);
	}

	private PageResponse<PublicRoomResponse> findPage(
		RoomListSearchCriteria criteria, int page, int size, Optional<Long> currentUserId) {
		Instant requestTime = Instant.now(clock);

		PageRequest pageable = PageRequest.of(
			page, size, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));
		RoomListReadService.RoomListReadResult readResult = roomListReadService.findPublicRoomsAt(
			criteria, pageable, currentUserId.orElse(null), requestTime);
		return toPageResponse(readResult, currentUserId);
	}

	private PageResponse<PublicRoomResponse> toPageResponse(
		RoomListReadService.RoomListReadResult readResult,
		Optional<Long> currentUserId) {
		Map<Long, GameSummary> gameSummaries = findGameSummaries(readResult.rooms().getContent());
		Page<PublicRoomResponse> response = readResult
			.rooms()
			.map(
				room -> PublicRoomResponse.from(
					room,
					readResult.effectiveStatusFor(room),
					getGameSummary(room, gameSummaries),
					availabilityFor(room, currentUserId, readResult)));
		return PageResponse.from(response);
	}

	private String normalizeKeyword(String keyword) {
		if (keyword == null) {
			return null;
		}
		String normalizedKeyword = keyword.strip();
		return normalizedKeyword.isEmpty()
			? null
			: normalizedKeyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
	}

	private Map<Long, GameSummary> findGameSummaries(List<Room> rooms) {
		Set<Long> gameIds = rooms.stream()
			.map(Room::getGameId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		return gameIds.isEmpty() ? Map.of() : gameQuery.findSummariesByIds(gameIds);
	}

	private GameSummary getGameSummary(Room room, Map<Long, GameSummary> gameSummaries) {
		if (room.getGameId() == null) {
			return null;
		}
		return Optional.ofNullable(gameSummaries.get(room.getGameId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
	}

	private RoomActionAvailability availabilityFor(
		Room room,
		Optional<Long> currentUserId,
		RoomListReadService.RoomListReadResult readResult) {
		return roomActionAvailabilityEvaluator.evaluate(new RoomActionAvailabilityFacts(
			room,
			readResult.effectiveStatusFor(room),
			readResult.requestTime(),
			currentUserId.isPresent(),
			currentUserId.filter(room.getHostUserId()::equals).isPresent(),
			readResult.activeParticipationRoomIds().contains(room.getId()),
			readResult.waitingRoomIds().contains(room.getId())));
	}
}
