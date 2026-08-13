package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery.GameRoomCount;
import cloud.bamsongi.albammate.game.dto.GameRankingItem;
import cloud.bamsongi.albammate.game.dto.GameRankingResponse;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 인기 게임 랭킹 조회 유스케이스를 담당한다.
 *
 * <p>{@code room}이 구현한 {@link GameRankingQuery}로 전체·앞으로 7일 두 랭킹의 게임별 집계를 받고, 집계에 등장한 게임 ID만 모아
 * {@link GameRepository}에서 표시 정보를 한 번에 조회해 결합한다. 순위는 집계 조회가 반환한 순서를 그대로 1부터 부여한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameRankingQueryService {

	private static final int RANKING_SIZE = 10;
	private static final Duration PAST_WEEK_WINDOW = Duration.ofDays(7);

	@NonNull private final GameRankingQuery gameRankingQuery;
	@NonNull private final GameRepository gameRepository;
	@NonNull private final Clock clock;

	/**
	 * 한 요청에서 고정한 기준 시각으로 전체 랭킹과 지난 7일 랭킹을 함께 조회한다.
	 *
	 * @return 각각 최대 {@value #RANKING_SIZE}개인 전체·지난 7일 랭킹
	 */
	public GameRankingResponse findRankings() {
		Instant requestTime = Instant.now(clock);
		List<GameRoomCount> overallCounts = gameRankingQuery.findOverallRanking(RANKING_SIZE);
		List<GameRoomCount> pastWeekCounts = gameRankingQuery.findRankingByPeriod(
			requestTime.minus(PAST_WEEK_WINDOW), requestTime, RANKING_SIZE);

		Map<Long, Game> gamesById = findGamesByIds(overallCounts, pastWeekCounts);
		return new GameRankingResponse(toItems(overallCounts, gamesById), toItems(pastWeekCounts, gamesById));
	}

	private Map<Long, Game> findGamesByIds(List<GameRoomCount> overallCounts, List<GameRoomCount> pastWeekCounts) {
		Set<Long> gameIds = Stream.concat(overallCounts.stream(), pastWeekCounts.stream())
			.map(GameRoomCount::gameId)
			.collect(Collectors.toSet());
		if (gameIds.isEmpty()) {
			return Map.of();
		}
		return gameRepository.findAllById(gameIds).stream().collect(Collectors.toMap(Game::getId, Function.identity()));
	}

	/** 집계에 등장했지만 표시 정보가 없는 게임 ID는 건너뛰어 랭킹에서 제외한다. */
	private List<GameRankingItem> toItems(List<GameRoomCount> counts, Map<Long, Game> gamesById) {
		List<GameRankingItem> items = new ArrayList<>();
		for (GameRoomCount count : counts) {
			Game game = gamesById.get(count.gameId());
			if (game == null) {
				continue;
			}
			items.add(GameRankingItem.from(items.size() + 1, game, count.roomCount()));
		}
		return items;
	}
}
