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

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RoomUpcomingRoomCountQuery implements UpcomingRoomCountQuery {

	private static final List<RoomStatus> EXCLUDED_STATUSES = List.of(RoomStatus.CANCELED, RoomStatus.FINISHED);

	private final RoomRepository roomRepository;

	/**
	 * 미래의 {@code GAME_FOCUSED} 방만 세되 {@code CANCELED}, {@code FINISHED} 상태는 제외한다. 빈 {@code
	 * gameIds}는 저장소를 조회하지 않고 빈 {@link Map}을 반환한다.
	 */
	@Override
	public Map<Long, Long> findUpcomingRoomCounts(Collection<Long> gameIds, Instant now) {
		if (gameIds.isEmpty()) {
			return Map.of();
		}

		return roomRepository
			.findUpcomingRoomCounts(gameIds, RoomType.GAME_FOCUSED, now, EXCLUDED_STATUSES)
			.stream()
			.collect(
				Collectors.toMap(
					RoomRepository.UpcomingRoomCount::getGameId,
					RoomRepository.UpcomingRoomCount::getRoomCount));
	}
}
