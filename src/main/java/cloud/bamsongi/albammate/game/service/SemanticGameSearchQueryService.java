package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearch;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchResponse;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * #871 SEARCH-04 공개 read 계약을 위해 #836 {@link SemanticGameSearch} core를 호출하고 결과를 기존
 * {@link GameListItem} 상세로 재조립하는 orchestrating 서비스다.
 *
 * <p>dense/lexical fallback 판정은 core가 소유하므로 이 서비스는 재구현하지 않는다. core 결과의
 * {@link SemanticGameSearchMode#UNAVAILABLE}만 {@code 503 SEARCH_UNAVAILABLE}로 매핑한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SemanticGameSearchQueryService {

	@NonNull private final SemanticGameSearch semanticGameSearch;
	@NonNull private final GameRepository gameRepository;
	@NonNull private final UpcomingRoomCountQuery upcomingRoomCountQuery;
	@NonNull private final UserPlayedGameRepository userPlayedGameRepository;
	@NonNull private final Clock clock;
	@NonNull private final GameFilterValidator gameFilterValidator;

	/**
	 * 의미 검색 요청을 검증하고 core 결과를 공개 {@link GameListItem} 상세로 재조립한다.
	 *
	 * @param request 검증을 마친 HTTP 요청
	 * @param currentUserId 인증 사용자 ID. 비로그인 요청이면 {@code null}
	 * @return relevance 순서를 보존한 공개 응답
	 */
	public SemanticGameSearchResponse search(SemanticGameSearchRequest request, Long currentUserId) {
		PlayedFilter playedFilter = request.getPlayedFilter();
		if (playedFilter != null && currentUserId == null) {
			throw new UnauthenticatedException();
		}
		GameListSearchCriteria criteria = GameListSearchCriteria.from(request.toGameListRequest());
		gameFilterValidator.validate(criteria);
		if (playedFilter != null) {
			criteria = criteria.withPlayedFilter(currentUserId);
		}
		SemanticGameSearchQuery query = new SemanticGameSearchQuery(
			request.getQuery(), criteria, request.getPage(), request.getSize());
		SemanticGameSearchResult result = semanticGameSearch.search(query);
		if (result.mode() == SemanticGameSearchMode.UNAVAILABLE) {
			throw new BusinessException(ErrorCode.SEARCH_UNAVAILABLE);
		}
		return assemble(result, criteria, currentUserId, request.getPage(), request.getSize());
	}

	private SemanticGameSearchResponse assemble(
		SemanticGameSearchResult result,
		GameListSearchCriteria criteria,
		Long currentUserId,
		int page,
		int size) {
		List<Long> orderedIds = result.content().stream().map(GameSummary::id).toList();
		if (orderedIds.isEmpty()) {
			return new SemanticGameSearchResponse(List.of(), page, size, result.hasNext(), result.mode());
		}
		Map<Long, Game> gamesById = new HashMap<>();
		for (Game game : gameRepository.findAllById(orderedIds)) {
			gamesById.put(game.getId(), game);
		}
		List<Game> orderedGames = orderedIds.stream()
			.map(gamesById::get)
			.filter(Objects::nonNull)
			.toList();
		Instant referenceTime = Instant.now(clock);
		List<Long> gameIds = orderedGames.stream().map(Game::getId).toList();
		Map<Long, Long> upcomingRoomCounts = gameIds.isEmpty()
			? Map.of()
			: upcomingRoomCountQuery.findUpcomingRoomCounts(gameIds, referenceTime);
		Set<Long> playedGameIds = currentUserId == null || criteria.getPlayedFilter() != null || gameIds.isEmpty()
			? Set.of()
			: new HashSet<>(userPlayedGameRepository.findGameIdsByUserIdAndGameIdIn(currentUserId, gameIds));
		List<GameListItem> content = orderedGames.stream()
			.map(game -> GameListItem.from(
				game,
				upcomingRoomCounts.getOrDefault(game.getId(), 0L),
				playedByMe(criteria, currentUserId, game.getId(), playedGameIds)))
			.toList();
		return new SemanticGameSearchResponse(content, page, size, result.hasNext(), result.mode());
	}

	private Boolean playedByMe(
		GameListSearchCriteria criteria, Long currentUserId, Long gameId, Set<Long> playedGameIds) {
		if (currentUserId == null) {
			return null;
		}
		if (criteria.getPlayedFilter() != null) {
			return criteria.getPlayedFilter() == PlayedFilter.PLAYED_ONLY;
		}
		return playedGameIds.contains(gameId);
	}
}
