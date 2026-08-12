package cloud.bamsongi.albammate.room.service.query;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery.GameRoomCount;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 게임별 인기 랭킹 집계를 실제로 세는 구현체다.
 *
 * <p>집계 대상은 게임을 정해놓고 모이는 방({@code GAME_FOCUSED}) 중 취소({@code CANCELED})되지 않은 방이다. 모집중·마감·종료
 * 상태는 모두 세고, 정렬과 상위 개수 제한은 {@link RoomRepository}의 집계 조회에 위임한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RoomGameRankingQuery implements GameRankingQuery {

	private static final List<RoomStatus> EXCLUDED_STATUSES = List.of(RoomStatus.CANCELED);

	@NonNull private final RoomRepository roomRepository;

	@Override
	public List<GameRoomCount> findOverallRanking(int limit) {
		return toGameRoomCounts(
			roomRepository.findGameRankingCounts(
				RoomType.GAME_FOCUSED, EXCLUDED_STATUSES, false, Instant.EPOCH, Instant.EPOCH,
				PageRequest.of(0, limit)));
	}

	@Override
	public List<GameRoomCount> findRankingByPeriod(Instant fromInclusive, Instant toExclusive, int limit) {
		return toGameRoomCounts(
			roomRepository.findGameRankingCounts(
				RoomType.GAME_FOCUSED, EXCLUDED_STATUSES, true, fromInclusive, toExclusive, PageRequest.of(0, limit)));
	}

	private List<GameRoomCount> toGameRoomCounts(List<RoomRepository.GameRankingCount> counts) {
		return counts.stream()
			.map(count -> new GameRoomCount(count.getGameId(), count.getRoomCount()))
			.toList();
	}
}
