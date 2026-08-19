package cloud.bamsongi.albammate.game.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import lombok.RequiredArgsConstructor;

/** 후보 카테고리를 먼저 필터링한 뒤 후보 ID 범위에서만 RANK-01을 적용한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AssistantGameCandidateQueryService implements AssistantGameCandidateQuery {

	private static final int MAX_CANDIDATES = 10;

	private final GameRepository gameRepository;
	private final GameRankingQuery gameRankingQuery;

	@Override
	public List<GameSummary> findCandidates(Criteria criteria) {
		GameListRequest request = new GameListRequest();
		request.setCategory(criteria.categories());
		request.setMechanism(criteria.mechanisms());
		request.setTheme(criteria.themes());
		request.setComplexityMax(criteria.complexityMax());
		request.setPlayerCount(criteria.playerCount());
		if (criteria.playTimeMax() != null) {
			request.setPlayTime(List.of(GamePlayTimeFilter.valueOf(criteria.playTimeMax())));
		}
		Specification<Game> specification = GameListSpecification.from(GameListSearchCriteria.from(request));
		if (criteria.gameId() != null) {
			specification = specification.and(
				(root, query, builder) -> builder.equal(root.get("id"), criteria.gameId()));
		}
		List<Game> catalogCandidates = gameRepository.findAll(specification);
		if (catalogCandidates.isEmpty()) {
			return List.of();
		}

		Map<Long, Long> roomCounts = gameRankingQuery.findOverallRankingForGameIds(
			catalogCandidates.stream().map(Game::getId).toList()).stream()
			.collect(Collectors.toMap(GameRankingQuery.GameRoomCount::gameId,
				GameRankingQuery.GameRoomCount::roomCount));
		return catalogCandidates.stream()
			.sorted(Comparator.comparingLong((Game game) -> roomCounts.getOrDefault(game.getId(), 0L))
				.reversed()
				.thenComparing(Game::getId))
			.limit(MAX_CANDIDATES)
			.map(game -> new GameSummary(game.getId(), game.getBggId(), game.getName()))
			.toList();
	}
}
