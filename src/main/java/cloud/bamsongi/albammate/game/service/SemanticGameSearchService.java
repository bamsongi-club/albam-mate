package cloud.bamsongi.albammate.game.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
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
import cloud.bamsongi.albammate.game.contract.SparseCandidateSource;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import jakarta.annotation.PreDestroy;

/**
 * 의미 검색 모델이 고른 후보를 그대로 응답하지 않고, 기존 게임 목록 검색과 같은 조건으로 다시 거르는 서비스다.
 *
 * dense candidate와 구조화된 sparse candidate를 공통 timeout 예산 안에서 독립 병렬로 생성한 뒤 RRF로
 * 결합한다. 두 후보 모두 읽을 수 없을 때만 키워드 검색으로 대체하며, 인원·시간·공개 범위 같은 P1 조건은
 * 모든 경로에 동일하게 적용한다. candidate 개수·timeout·RRF {@code k} 값의 근거는
 * docs/measurements/search-04e-hybrid-rrf-regression.md를 따른다(ADR-0088).
 */
@Service
@Transactional(readOnly = true)
public class SemanticGameSearchService implements SemanticGameSearch {

	private static final int RRF_K = 60;

	private static final int CANDIDATE_POOL_SIZE = 8;

	private final GameRepository gameRepository;
	private final DenseCandidateSource candidateSource;
	private final SparseCandidateSource sparseCandidateSource;
	private final Duration hybridCandidateTimeout;
	private final ExecutorService denseExecutor = newDaemonPool("semantic-search-dense", CANDIDATE_POOL_SIZE);
	private final ExecutorService sparseExecutor = newBoundedDaemonPool("semantic-search-sparse", CANDIDATE_POOL_SIZE);

	public SemanticGameSearchService(
		GameRepository gameRepository,
		DenseCandidateSource candidateSource,
		SparseCandidateSource sparseCandidateSource,
		@Value("${app.search.hybrid.candidate-timeout:6s}")
		Duration hybridCandidateTimeout) {
		this.gameRepository = gameRepository;
		this.candidateSource = candidateSource;
		this.sparseCandidateSource = sparseCandidateSource;
		this.hybridCandidateTimeout = hybridCandidateTimeout;
	}

	@Override
	public SemanticGameSearchResult search(SemanticGameSearchQuery query) {
		long deadlineNanos = System.nanoTime() + hybridCandidateTimeout.toNanos();
		Future<List<DenseCandidateSource.Candidate>> denseFuture = denseExecutor
			.submit(() -> candidateSource.findCandidates(query.rawQuery()));
		Future<List<DenseCandidateSource.Candidate>> sparseFuture = submitSparseCandidates(query.rawQuery(),
			deadlineNanos);
		CandidateOutcome dense = await(denseFuture, deadlineNanos);
		CandidateOutcome sparse = sparseFuture == null ? CandidateOutcome.failure()
			: await(sparseFuture, deadlineNanos);

		if (dense.succeeded() && sparse.succeeded()) {
			return semanticResult(query, fuseByReciprocalRank(dense.candidates(), sparse.candidates()),
				SemanticGameSearchMode.SEMANTIC);
		}
		if (dense.succeeded()) {
			return semanticResult(query, dense.candidates(), SemanticGameSearchMode.SEMANTIC);
		}
		if (sparse.succeeded()) {
			return semanticResult(query, sparse.candidates(), SemanticGameSearchMode.SPARSE_FALLBACK);
		}
		return lexicalFallback(query);
	}

	@PreDestroy
	void shutdown() {
		denseExecutor.shutdownNow();
		sparseExecutor.shutdownNow();
	}

	private CandidateOutcome await(Future<List<DenseCandidateSource.Candidate>> future, long deadlineNanos) {
		try {
			long remainingNanos = deadlineNanos - System.nanoTime();
			return CandidateOutcome.success(future.get(Math.max(remainingNanos, 0), TimeUnit.NANOSECONDS));
		} catch (TimeoutException exception) {
			future.cancel(true);
			return CandidateOutcome.failure();
		} catch (ExecutionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof SemanticSearchUnavailableException) {
				return CandidateOutcome.failure();
			}
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException(cause);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private Future<List<DenseCandidateSource.Candidate>> submitSparseCandidates(String rawQuery, long deadlineNanos) {
		try {
			return sparseExecutor.submit(() -> findSparseCandidates(rawQuery, deadlineNanos));
		} catch (RejectedExecutionException ignored) {
			return null;
		}
	}

	private List<DenseCandidateSource.Candidate> findSparseCandidates(String rawQuery, long deadlineNanos) {
		if (sparseCandidateSource instanceof SparseCandidateSource.DeadlineAware deadlineAware) {
			long remainingNanos = deadlineNanos - System.nanoTime();
			if (remainingNanos <= 0) {
				throw new SemanticSearchUnavailableException();
			}
			return deadlineAware.findCandidates(rawQuery, Duration.ofNanos(remainingNanos));
		}
		return sparseCandidateSource.findCandidates(rawQuery);
	}

	/**
	 * 두 candidate 목록을 Reciprocal Rank Fusion으로 결합한다. 원본 relevance 값의 스케일 차이에 영향받지
	 * 않도록 순위만 사용한다. 같은 목록 안에서 relevance가 같으면 gameId 오름차순으로 순위를 매겨 결정적으로
	 * 만든다. 결합 점수는 이후 정렬 입력으로만 쓰고 공개 응답에는 노출하지 않는다.
	 */
	private List<DenseCandidateSource.Candidate> fuseByReciprocalRank(
		List<DenseCandidateSource.Candidate> first, List<DenseCandidateSource.Candidate> second) {
		Map<Long, Double> scoreByGameId = new LinkedHashMap<>();
		addReciprocalRankScores(first, scoreByGameId);
		addReciprocalRankScores(second, scoreByGameId);
		List<DenseCandidateSource.Candidate> fused = new ArrayList<>();
		scoreByGameId.forEach((gameId, score) -> fused.add(new DenseCandidateSource.Candidate(gameId, score)));
		fused.sort(Comparator.comparingDouble(DenseCandidateSource.Candidate::relevance).reversed()
			.thenComparing(DenseCandidateSource.Candidate::gameId));
		return fused;
	}

	private void addReciprocalRankScores(
		List<DenseCandidateSource.Candidate> candidates, Map<Long, Double> scoreByGameId) {
		Map<Long, Double> bestRelevanceByGameId = new LinkedHashMap<>();
		for (DenseCandidateSource.Candidate candidate : candidates) {
			bestRelevanceByGameId.merge(candidate.gameId(), candidate.relevance(), Math::max);
		}
		List<Long> rankedGameIds = new ArrayList<>(bestRelevanceByGameId.keySet());
		rankedGameIds.sort(Comparator.<Long>comparingDouble(bestRelevanceByGameId::get).reversed()
			.thenComparing(gameId -> gameId));
		for (int index = 0; index < rankedGameIds.size(); index++) {
			int rank = index + 1;
			scoreByGameId.merge(rankedGameIds.get(index), 1.0 / (RRF_K + rank), Double::sum);
		}
	}

	private SemanticGameSearchResult semanticResult(
		SemanticGameSearchQuery query, List<DenseCandidateSource.Candidate> candidates, SemanticGameSearchMode mode) {
		Map<Long, Double> relevanceByGameId = new LinkedHashMap<>();
		for (DenseCandidateSource.Candidate candidate : candidates) {
			relevanceByGameId.merge(candidate.gameId(), candidate.relevance(), Math::max);
		}
		if (relevanceByGameId.isEmpty()) {
			return new SemanticGameSearchResult(mode, List.of(), false);
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
		return page(mode, orderedGames, query.page(), query.size());
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

	private static ExecutorService newDaemonPool(String threadNamePrefix, int poolSize) {
		return Executors.newFixedThreadPool(poolSize, newDaemonThreadFactory(threadNamePrefix));
	}

	private static ExecutorService newBoundedDaemonPool(String threadNamePrefix, int poolSize) {
		return new ThreadPoolExecutor(poolSize, poolSize, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
			newDaemonThreadFactory(threadNamePrefix), new ThreadPoolExecutor.AbortPolicy());
	}

	private static ThreadFactory newDaemonThreadFactory(String threadNamePrefix) {
		AtomicInteger threadCount = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, threadNamePrefix + "-" + threadCount.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	private record CandidateOutcome(List<DenseCandidateSource.Candidate> candidates, boolean succeeded) {

		static CandidateOutcome success(List<DenseCandidateSource.Candidate> candidates) {
			return new CandidateOutcome(candidates, true);
		}

		static CandidateOutcome failure() {
			return new CandidateOutcome(List.of(), false);
		}
	}
}
