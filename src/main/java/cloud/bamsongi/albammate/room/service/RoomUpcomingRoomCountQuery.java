package cloud.bamsongi.albammate.room.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import lombok.RequiredArgsConstructor;

/**
 * 게임별로 앞으로 열릴 모임 수를 실제로 세는 구현체다.
 *
 * <p>세는 대상은 게임을 정해놓고 모이는 방({@code GAME_FOCUSED}) 중 아직 시작하지 않았고, 취소({@code CANCELED})되거나
 * 끝나지({@code FINISHED}) 않은 방이다. 사람 중심 방({@code PERSON_FOCUSED})은 특정 게임의 모임이 아니므로 세지 않는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RoomUpcomingRoomCountQuery implements UpcomingRoomCountQuery {

	private static final List<RoomStatus> EXCLUDED_STATUSES = List.of(RoomStatus.CANCELED, RoomStatus.FINISHED);

	private final RoomRepository roomRepository;

	@Override
	public Map<Long, Long> findUpcomingRoomCounts(Instant now) {
		return toUpcomingRoomCounts(
			roomRepository.findAllUpcomingRoomCounts(RoomType.GAME_FOCUSED, now, EXCLUDED_STATUSES));
	}

	/**
	 * {@code gameIds}가 비어 있으면 셀 게임이 없으므로 DB를 조회하지 않고 바로 빈 {@link Map}을 돌려준다.
	 */
	@Override
	public Map<Long, Long> findUpcomingRoomCounts(Collection<Long> gameIds, Instant now) {
		if (gameIds.isEmpty()) {
			return Map.of();
		}

		return toUpcomingRoomCounts(
			roomRepository.findUpcomingRoomCounts(gameIds, RoomType.GAME_FOCUSED, now, EXCLUDED_STATUSES));
	}

	private Map<Long, Long> toUpcomingRoomCounts(
		List<RoomRepository.UpcomingRoomCount> upcomingRoomCounts) {
		return upcomingRoomCounts.stream()
			.collect(
				Collectors.toMap(
					RoomRepository.UpcomingRoomCount::getGameId,
					RoomRepository.UpcomingRoomCount::getRoomCount));
	}
}
