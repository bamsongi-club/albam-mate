package cloud.bamsongi.albammate.game.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.GameRankingQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
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
	private static final Comparator<RankedCandidate> RANKING_ORDER = Comparator
		.comparingLong(RankedCandidate::roomCount)
		.reversed()
		.thenComparing(candidate -> candidate.summary().id());

	private final GameRepository gameRepository;
	private final GameRankingQuery gameRankingQuery;
	private final GameCategoryRepository gameCategoryRepository;
	private final GameMechanismRepository gameMechanismRepository;
	private final GameThemeRepository gameThemeRepository;

	@Override
	public void validateCriteria(Criteria criteria) {
		Objects.requireNonNull(criteria, "criteria");
		validateCodes(criteria.categories(), gameCategoryRepository::countByCodeIn);
		validateCodes(criteria.mechanisms(), gameMechanismRepository::countByCodeInAndIsPublicTrue);
		validateCodes(criteria.themes(), gameThemeRepository::countByCodeIn);
		if (criteria.gameId() != null && !gameRepository.existsById(criteria.gameId())) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

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
		List<RankedCandidate> topCandidates = new ArrayList<>();
		Slice<GameSummary> page;
		int pageNumber = 0;
		do {
			page = gameRepository.findCandidateSummaries(
				specification, PageRequest.of(pageNumber++, CANDIDATE_BATCH_SIZE));
			if (page.isEmpty()) {
				break;
			}
			Map<Long, Long> roomCounts = gameRankingQuery.findOverallRankingForGameIds(
				page.getContent().stream().map(GameSummary::id).toList()).stream()
				.collect(Collectors.toMap(GameRankingQuery.GameRoomCount::gameId,
					GameRankingQuery.GameRoomCount::roomCount));
			for (GameSummary summary : page) {
				retainTop(topCandidates, new RankedCandidate(summary, roomCounts.getOrDefault(summary.id(), 0L)));
			}
		} while (page.hasNext());
		return topCandidates.stream().sorted(RANKING_ORDER).map(RankedCandidate::summary).toList();
	}

	private void retainTop(List<RankedCandidate> topCandidates, RankedCandidate candidate) {
		topCandidates.add(candidate);
		topCandidates.sort(RANKING_ORDER);
		if (topCandidates.size() > MAX_CANDIDATES) {
			topCandidates.remove(topCandidates.size() - 1);
		}
	}

	private void validateCodes(List<String> requestedCodes, Function<List<String>, Long> countByCodes) {
		List<String> codes = requestedCodes.stream().filter(Objects::nonNull).distinct().toList();
		if (!codes.isEmpty() && countByCodes.apply(codes) != codes.size()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private record RankedCandidate(GameSummary summary, long roomCount) {
	}
}
