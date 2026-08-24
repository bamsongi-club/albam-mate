package cloud.bamsongi.albammate.game.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.AssistantRecommendationCandidate;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

/** 후보 카테고리를 먼저 필터링한 뒤 후보 ID 범위에서만 RANK-01을 적용한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AssistantGameCandidateQueryService implements AssistantGameCandidateQuery {

	private static final int MAX_CANDIDATES = 10;
	private static final int CANDIDATE_BATCH_SIZE = 500;
	private static final Comparator<RankedCandidate<?>> RANKING_ORDER = Comparator
		.comparingLong((RankedCandidate<?> candidate) -> candidate.roomCount())
		.reversed()
		.thenComparing(RankedCandidate::id);

	private final GameRepository gameRepository;
	private final GameRankingQuery gameRankingQuery;
	private final GameFilterValidator gameFilterValidator;

	@Override
	public void validateCriteria(Criteria criteria) {
		gameFilterValidator.validate(toGameListSearchCriteria(criteria));
		if (criteria.gameId() != null && !gameRepository.existsById(criteria.gameId())) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	@Override
	public List<AssistantRecommendationCandidate> findCandidates(Criteria criteria) {
		return findRankedCandidates(criteria, gameRepository::findAssistantRecommendationCandidates,
			AssistantRecommendationCandidate::id);
	}

	private <T> List<T> findRankedCandidates(
		Criteria criteria,
		java.util.function.BiFunction<Specification<Game>, PageRequest, Slice<T>> candidateReader,
		java.util.function.Function<T, Long> idExtractor) {
		Specification<Game> specification = GameListSpecification.from(toGameListSearchCriteria(criteria));
		if (criteria.gameId() != null) {
			specification = specification.and(
				(root, query, builder) -> builder.equal(root.get("id"), criteria.gameId()));
		}
		List<RankedCandidate<T>> topCandidates = new ArrayList<>();
		Slice<T> page;
		int pageNumber = 0;
		do {
			page = candidateReader.apply(
				specification, PageRequest.of(pageNumber++, CANDIDATE_BATCH_SIZE));
			if (page.isEmpty()) {
				break;
			}
			Map<Long, Long> roomCounts = gameRankingQuery.findOverallRankingForGameIds(
				page.getContent().stream().map(idExtractor).toList()).stream()
				.collect(Collectors.toMap(GameRankingQuery.GameRoomCount::gameId,
					GameRankingQuery.GameRoomCount::roomCount));
			for (T candidate : page) {
				retainTop(topCandidates, new RankedCandidate<>(candidate,
					roomCounts.getOrDefault(idExtractor.apply(candidate), 0L), idExtractor.apply(candidate)));
			}
		} while (page.hasNext());
		return topCandidates.stream().sorted(RANKING_ORDER).map(RankedCandidate::candidate).toList();
	}

	private <T> void retainTop(List<RankedCandidate<T>> topCandidates, RankedCandidate<T> candidate) {
		topCandidates.add(candidate);
		topCandidates.sort(RANKING_ORDER);
		if (topCandidates.size() > MAX_CANDIDATES) {
			topCandidates.remove(topCandidates.size() - 1);
		}
	}

	private GameListSearchCriteria toGameListSearchCriteria(Criteria criteria) {
		GameListRequest request = new GameListRequest();
		request.setCategory(criteria.categories());
		request.setMechanism(criteria.mechanisms());
		request.setTheme(criteria.themes());
		request.setComplexityMax(criteria.complexityMax());
		request.setPlayerCount(criteria.playerCount());
		if (criteria.playTimeMax() != null) {
			request.setPlayTime(List.of(GamePlayTimeFilter.valueOf(criteria.playTimeMax())));
		}
		return GameListSearchCriteria.from(request);
	}

	private record RankedCandidate<T>(T candidate, long roomCount, long id) {
	}
}
