package cloud.bamsongi.albammate.game.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearch;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import lombok.RequiredArgsConstructor;

/**
 * 의미 검색 모델이 고른 후보를 그대로 응답하지 않고, 기존 게임 목록 검색과 같은 조건으로 다시 거르는 서비스다.
 *
 * 후보를 읽을 수 없을 때만 키워드 검색으로 대체하며, 인원·시간·공개 범위 같은 P1 조건은 두 경로에
 * 모두 적용한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SemanticGameSearchService implements SemanticGameSearch {

	private final GameRepository gameRepository;
	private final DenseCandidateSource candidateSource;

	@Override
	public SemanticGameSearchResult search(SemanticGameSearchQuery query) {
		List<DenseCandidateSource.Candidate> candidates;
		try {
			candidates = candidateSource.findCandidates(query.rawQuery());
		} catch (SemanticSearchUnavailableException ignored) {
			return lexicalFallback(query);
		}
		return semanticResult(query, candidates);
	}

	private SemanticGameSearchResult semanticResult(
		SemanticGameSearchQuery query, List<DenseCandidateSource.Candidate> candidates) {
		Map<Long, Double> relevanceByGameId = new LinkedHashMap<>();
		for (DenseCandidateSource.Candidate candidate : candidates) {
			relevanceByGameId.merge(candidate.gameId(), candidate.relevance(), Math::max);
		}
		if (relevanceByGameId.isEmpty()) {
			return new SemanticGameSearchResult(SemanticGameSearchMode.SEMANTIC, List.of(), false);
		}
		List<Game> filteredGames = gameRepository.findAll(
			GameListSpecification.from(query.criteria()).and((root, criteriaQuery, criteriaBuilder) -> root.get("id")
				.in(relevanceByGameId.keySet())));
		Map<Long, Game> gamesById = filteredGames.stream().collect(
			java.util.stream.Collectors.toMap(Game::getId, game -> game));
		List<Game> orderedGames = new ArrayList<>(gamesById.values());
		orderedGames.sort(Comparator
			.comparing((Game game) -> relevanceByGameId.get(game.getId()), Comparator.reverseOrder())
			.thenComparing(Game::getName)
			.thenComparing(Game::getId));
		return page(SemanticGameSearchMode.SEMANTIC, orderedGames, query.page(), query.size());
	}

	private SemanticGameSearchResult lexicalFallback(SemanticGameSearchQuery query) {
		try {
			Slice<GameSummary> games = gameRepository.findLexicalFallbackSummaries(
				GameListSpecification.from(query.criteria().withKeyword(query.rawQuery())),
				PageRequest.of(query.page(), query.size()));
			return new SemanticGameSearchResult(SemanticGameSearchMode.LEXICAL_FALLBACK, games.getContent(),
				games.hasNext());
		} catch (DataAccessResourceFailureException ignored) {
			return new SemanticGameSearchResult(SemanticGameSearchMode.UNAVAILABLE, List.of(), false);
		}
	}

	private SemanticGameSearchResult page(SemanticGameSearchMode mode, List<Game> games, int page, int size) {
		long requestedStart = (long)page * size;
		int start = requestedStart >= games.size() ? games.size() : (int)requestedStart;
		int end = Math.min(start + size, games.size());
		List<GameSummary> content = games.subList(start, end).stream()
			.map(game -> new GameSummary(game.getId(), game.getBggId(), game.getName()))
			.toList();
		return new SemanticGameSearchResult(mode, content, end < games.size());
	}
}
