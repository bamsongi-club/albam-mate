package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 게임 목록 조회 유스케이스를 담당한다.
 *
 * <p>검색 조건 검증, 페이지네이션, 예정 모임 수 집계, 해 본 게임 표시를 하나의 저장소 동적 조회 경계로 조립한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameQueryService {

	@NonNull private final GameRepository gameRepository;
	@NonNull private final Clock clock;
	@NonNull private final UpcomingRoomCountQuery upcomingRoomCountQuery;
	@NonNull private final UserPlayedGameRepository userPlayedGameRepository;
	@NonNull private final GameFilterValidator gameFilterValidator;
	@NonNull private final JdbcTemplate jdbcTemplate;

	/**
	 * 게임 목록 조건을 하나의 저장소 동적 조회에 적용하고 예정 모임 수를 조립한다.
	 *
	 * <p>{@code playedFilter}가 있으면 인증 사용자가 필요하므로 {@code currentUserId}가 {@code null}이면 거절한다.
	 *
	 * @param request HTTP 목록 요청
	 * @param currentUserId 인증 사용자 ID. 비로그인 요청이면 {@code null}
	 * @return 예정 모임 수가 포함된 게임 목록 Slice
	 */
	public Slice<GameListItem> findPage(GameListRequest request, Long currentUserId) {
		Instant referenceTime = Instant.now(clock);
		PlayedFilter playedFilter = request.getPlayedFilter();
		if (playedFilter != null && currentUserId == null) {
			throw new UnauthenticatedException();
		}
		GameListSearchCriteria criteria = GameListSearchCriteria.from(request);
		gameFilterValidator.validate(criteria);
		if (playedFilter != null) {
			criteria = criteria.withPlayedFilter(currentUserId);
		}
		return findPage(criteria, request.getPage(), request.getSize(), currentUserId, referenceTime);
	}

	private Slice<GameListItem> findPage(
		GameListSearchCriteria criteria, int page, int size, Long currentUserId, Instant referenceTime) {
		boolean similaritySearch = GameListSpecification.usesSimilaritySearch(criteria.getKeyword());
		Pageable pageable = similaritySearch
			? PageRequest.of(page, size)
			: PageRequest.of(
				page,
				size,
				Sort.by(
					Sort.Order.desc("popularityScore"),
					Sort.Order.asc("name"),
					Sort.Order.asc("id")));
		Map<Long, Long> upcomingRoomCounts = Map.of();
		if (criteria.isUpcomingOnly()) {
			upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(referenceTime);
			if (upcomingRoomCounts.isEmpty()) {
				return new SliceImpl<>(List.of(), pageable, false);
			}
			criteria = criteria.withUpcomingGameIds(upcomingRoomCounts.keySet());
		}
		if (similaritySearch) {
			configureSimilarityThreshold();
		}

		GameListSearchCriteria pageCriteria = criteria;
		// 필터·검색어가 전혀 없는 요청만 전체 건수를 계산한다(#1055). 그 외에는 count 없는 Slice 조회를 유지한다.
		boolean includeTotals = pageCriteria.isFilterless();
		Slice<Game> games = gameRepository.findBy(
			GameListSpecification.from(pageCriteria, true),
			includeTotals ? query -> query.page(pageable) : query -> query.slice(pageable));
		if (games.isEmpty()) {
			// 조립할 게임이 없으므로 예정 모임 수와 해 본 게임 조회를 건너뛰고 페이지 메타데이터만 그대로 전달한다.
			return games instanceof Page<Game> gamePage
				? new PageImpl<>(List.of(), pageable, gamePage.getTotalElements())
				: new SliceImpl<>(List.of(), pageable, games.hasNext());
		}

		if (!criteria.isUpcomingOnly()) {
			upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(
				games.getContent().stream().map(Game::getId).toList(),
				referenceTime);
		}

		Map<Long, Long> counts = upcomingRoomCounts;
		Set<Long> playedGameIds = currentUserId == null || pageCriteria.getPlayedFilter() != null
			? Set.of()
			: new HashSet<>(
				userPlayedGameRepository.findGameIdsByUserIdAndGameIdIn(
					currentUserId,
					games.getContent().stream().map(Game::getId).toList()));
		return games.map(
			game -> GameListItem.from(
				game,
				counts.getOrDefault(game.getId(), 0L),
				playedByMe(pageCriteria, currentUserId, game.getId(), playedGameIds)));
	}

	private void configureSimilarityThreshold() {
		jdbcTemplate.execute((ConnectionCallback<Void>)connection -> {
			if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
				return null;
			}
			try (var statement = connection.prepareStatement("select set_limit(0.3::real)")) {
				statement.execute();
			}
			return null;
		});
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
