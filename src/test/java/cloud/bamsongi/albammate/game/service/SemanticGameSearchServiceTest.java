package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import cloud.bamsongi.albammate.game.contract.SparseCandidateSource;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;

class SemanticGameSearchServiceTest {

	@Test
	void T2_filtered_결과수_밖의_페이지는_빈_SEMANTIC과_hasNext_false를_반환한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		Game game = game(10L, 1010L, "전략 게임");
		when(candidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(game.getId(), 0.9)));
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(game));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("전략 게임", 1, 1));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(), result.content());
		assertFalse(result.hasNext());
	}

	@Test
	void T3_dense_정상_빈_결과는_필터완화나_이름검색없이_빈_SEMANTIC으로_반환한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenReturn(List.of());

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("전략 게임"));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(), result.content());
		assertFalse(result.hasNext());
	}

	@Test
	void T4_dense_실패면_같은_필터의_LEXICAL_FALLBACK을_반환한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		GameSummary lexicalMatch = new GameSummary(11L, 1011L, "전략 게임");
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(lexicalMatch), PageRequest.of(1, 1), true));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("전략 게임", 1, 1));

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
		assertEquals(List.of(lexicalMatch.id()), result.content().stream().map(game -> game.id()).toList());
		assertTrue(result.hasNext());
		ArgumentCaptor<PageRequest> pageable = ArgumentCaptor.forClass(PageRequest.class);
		org.mockito.Mockito.verify(gameRepository).findLexicalFallbackSummaries(any(Specification.class),
			pageable.capture());
		assertEquals(1, pageable.getValue().getPageNumber());
		assertEquals(1, pageable.getValue().getPageSize());
	}

	@Test
	void T5_dense와_lexical이_모두_불능이면_원문과_상세없는_UNAVAILABLE만_반환한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		String rawQuery = "사용자 비밀 query";
		when(candidateSource.findCandidates(rawQuery)).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenThrow(new DataAccessResourceFailureException("database detail"));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query(rawQuery));

		assertEquals(SemanticGameSearchMode.UNAVAILABLE, result.mode());
		assertEquals(List.of(), result.content());
		assertFalse(result.toString().contains(rawQuery));
		assertFalse(result.toString().contains("database detail"));
	}

	@Test
	void T4_dense_source의_예상하지_않은_오류는_lexical_fallback으로_숨기지_않는다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenThrow(new IllegalStateException("candidate bug"));

		assertThrows(IllegalStateException.class,
			() -> service(gameRepository, candidateSource).search(query("전략 게임")));
	}

	@Test
	void T4_lexical_fallback은_DB_Slice_GameSummary로_P1_페이지경계를_위임한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		GameSummary popular = new GameSummary(22L, 1022L, "가나다");
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(popular), PageRequest.of(0, 1), true));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("전략 게임", 0, 1));

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
		assertEquals(List.of(popular), result.content());
		assertTrue(result.hasNext());
		org.mockito.Mockito.verify(gameRepository, org.mockito.Mockito.never()).findAll(any(Specification.class));
	}

	@Test
	void T5_lexical_DB의_예상하지_않은_오류는_UNAVAILABLE로_숨기지_않는다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenThrow(new IllegalStateException("repository bug"));

		assertThrows(IllegalStateException.class,
			() -> service(gameRepository, candidateSource).search(query("전략 게임")));
	}

	@Test
	void T2_semantic_후보_재검증_저장소오류는_fallback으로_숨기지_않는다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(1L, 0.9)));
		when(gameRepository.findAll(any(Specification.class))).thenThrow(new IllegalStateException("repository bug"));

		assertThrows(IllegalStateException.class,
			() -> service(gameRepository, candidateSource).search(query("전략 게임")));
	}

	@Test
	void T6_내부_결과계약은_점수_vector_query를_노출하지_않고_기존_P1_목록과_분리한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		Game game = game(12L, 1012L, "기존 목록 게임");
		when(candidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(game.getId(), 0.9)));
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(game));

		SemanticGameSearchResult result = service(gameRepository, candidateSource).search(query("기존 목록"));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(game.getId()), result.content().stream().map(item -> item.id()).toList());
		List<String> resultFields = List.of(SemanticGameSearchResult.class.getRecordComponents()).stream()
			.map(component -> component.getName()).toList();
		assertEquals(List.of("mode", "content", "hasNext"), resultFields);
		assertTrue(resultFields.stream().noneMatch(field -> field.contains("score") || field.contains("vector")
			|| field.contains("query")));
	}

	@Test
	void HYBRID_T1_dense와_sparse는_한쪽_지연이_다른쪽_시작을_막지_않고_독립_병렬로_시작한다() throws InterruptedException {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		AtomicLong denseStartedAtNanos = new AtomicLong();
		AtomicLong sparseStartedAtNanos = new AtomicLong();
		CountDownLatch sparseStarted = new CountDownLatch(1);
		when(candidateSource.findCandidates(anyString())).thenAnswer(invocation -> {
			denseStartedAtNanos.set(System.nanoTime());
			sparseStarted.await(2, TimeUnit.SECONDS);
			Thread.sleep(300);
			return List.of(new DenseCandidateSource.Candidate(1L, 0.9));
		});
		when(sparseCandidateSource.findCandidates(anyString())).thenAnswer(invocation -> {
			sparseStartedAtNanos.set(System.nanoTime());
			sparseStarted.countDown();
			return List.of(new DenseCandidateSource.Candidate(2L, 5.0));
		});
		Game gameOne = game(1L, 1001L, "Dense 후보");
		Game gameTwo = game(2L, 1002L, "Sparse 후보");
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(gameOne, gameTwo));

		service(gameRepository, candidateSource, sparseCandidateSource).search(query("일꾼 놓기 게임"));

		long startGapMillis = Math.abs(denseStartedAtNanos.get() - sparseStartedAtNanos.get()) / 1_000_000;
		assertTrue(startGapMillis < 250,
			"dense와 sparse는 서로 기다리지 않고 거의 동시에 시작해야 한다. 실제 간격: " + startGapMillis + "ms");
	}

	@Test
	void HYBRID_T2_동일입력_동일순서를_유지하고_동점은_gameId_오름차순으로_수렴한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		Game lowerGameId = game(1L, 1001L, "Bravo");
		Game higherGameId = game(2L, 1002L, "Alpha");
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(lowerGameId, higherGameId));
		when(sparseCandidateSource.findCandidates(anyString()))
			.thenReturn(
				List.of(new DenseCandidateSource.Candidate(1L, 5.0), new DenseCandidateSource.Candidate(2L, 5.0)));

		when(candidateSource.findCandidates(anyString()))
			.thenReturn(
				List.of(new DenseCandidateSource.Candidate(2L, 0.9), new DenseCandidateSource.Candidate(1L, 0.9)));
		SemanticGameSearchResult firstOrder = service(gameRepository, candidateSource, sparseCandidateSource)
			.search(query("일꾼 놓기 게임"));

		when(candidateSource.findCandidates(anyString()))
			.thenReturn(
				List.of(new DenseCandidateSource.Candidate(1L, 0.9), new DenseCandidateSource.Candidate(2L, 0.9)));
		SemanticGameSearchResult secondOrder = service(gameRepository, candidateSource, sparseCandidateSource)
			.search(query("일꾼 놓기 게임"));

		assertEquals(SemanticGameSearchMode.SEMANTIC, firstOrder.mode());
		assertEquals(List.of(1L, 2L), firstOrder.content().stream().map(GameSummary::id).toList());
		assertEquals(List.of(1L, 2L), secondOrder.content().stream().map(GameSummary::id).toList());
	}

	@Test
	void HYBRID_T3_dense만_성공하면_기존_dense_only와_같은_SEMANTIC으로_수렴한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		Game game = game(10L, 1010L, "Dense 단독 게임");
		when(candidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(game.getId(), 0.9)));
		when(sparseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(game));

		SemanticGameSearchResult result = service(gameRepository, candidateSource, sparseCandidateSource)
			.search(query("전략 게임"));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(game.getId()), result.content().stream().map(GameSummary::id).toList());
	}

	@Test
	void HYBRID_T3_sparse만_성공하면_SPARSE_FALLBACK으로_수렴한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		Game game = game(11L, 1011L, "Sparse 단독 게임");
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(sparseCandidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(game.getId(), 6.0)));
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(game));

		SemanticGameSearchResult result = service(gameRepository, candidateSource, sparseCandidateSource)
			.search(query("일꾼 놓기 게임"));

		assertEquals(SemanticGameSearchMode.SPARSE_FALLBACK, result.mode());
		assertEquals(List.of(game.getId()), result.content().stream().map(GameSummary::id).toList());
	}

	@Test
	void HYBRID_T3_둘다_실패하면_기존_LEXICAL_FALLBACK_경로로_수렴한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		GameSummary lexicalMatch = new GameSummary(21L, 1021L, "폴백 게임");
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(sparseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(lexicalMatch), PageRequest.of(0, 10), false));

		SemanticGameSearchResult result = service(gameRepository, candidateSource, sparseCandidateSource)
			.search(query("전략 게임"));

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
		assertEquals(List.of(lexicalMatch.id()), result.content().stream().map(GameSummary::id).toList());
	}

	@Test
	void HYBRID_T4_공통_timeout을_넘긴_후보는_실패로_수렴해_LEXICAL_FALLBACK으로_이어진다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenAnswer(invocation -> {
			Thread.sleep(500);
			return List.of(new DenseCandidateSource.Candidate(1L, 0.9));
		});
		when(sparseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		GameSummary lexicalMatch = new GameSummary(31L, 1031L, "timeout 뒤 폴백 게임");
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(lexicalMatch), PageRequest.of(0, 10), false));

		SemanticGameSearchResult result = service(gameRepository, candidateSource, sparseCandidateSource,
			java.time.Duration.ofMillis(50)).search(query("전략 게임"));

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
		assertEquals(List.of(lexicalMatch.id()), result.content().stream().map(GameSummary::id).toList());
	}

	@Test
	void ISSUE_1001_T1_이미_만료된_deadline은_deadlineAware_sparse_source를_호출하지_않는다() {
		AtomicInteger sparseCalls = new AtomicInteger();
		SparseCandidateSource sparseCandidateSource = new SparseCandidateSource.DeadlineAware() {
			@Override
			public List<DenseCandidateSource.Candidate> findCandidates(String rawQuery) {
				throw new AssertionError("만료된 deadline에서는 legacy sparse 경로를 호출하면 안 됩니다.");
			}

			@Override
			public List<DenseCandidateSource.Candidate> findCandidates(String rawQuery, Duration remainingTimeout) {
				sparseCalls.incrementAndGet();
				return List.of();
			}
		};
		SemanticGameSearchService service = service(org.mockito.Mockito.mock(GameRepository.class),
			org.mockito.Mockito.mock(DenseCandidateSource.class), sparseCandidateSource);
		try {
			assertThrows(SemanticSearchUnavailableException.class,
				() -> ReflectionTestUtils.invokeMethod(service, "findSparseCandidates", "만료된 검색",
					System.nanoTime() - 1));
			assertEquals(0, sparseCalls.get());
		} finally {
			service.shutdown();
		}
	}

	@Test
	void HYBRID_T5_동시_요청_두_건의_dense_조회는_서로를_차단하지_않고_병렬로_처리된다() throws Exception {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		when(sparseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of());
		CountDownLatch bothDenseCallsStarted = new CountDownLatch(2);
		AtomicInteger overlappedStartCount = new AtomicInteger();
		when(candidateSource.findCandidates(anyString())).thenAnswer(invocation -> {
			bothDenseCallsStarted.countDown();
			if (bothDenseCallsStarted.await(1, TimeUnit.SECONDS)) {
				overlappedStartCount.incrementAndGet();
			}
			return List.of(new DenseCandidateSource.Candidate(1L, 0.9));
		});
		SemanticGameSearchService service = service(gameRepository, candidateSource, sparseCandidateSource);

		ExecutorService callers = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = callers.submit(() -> service.search(query("전략 게임")));
			Future<?> second = callers.submit(() -> service.search(query("전략 게임")));
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
		} finally {
			callers.shutdownNow();
		}

		assertEquals(2, overlappedStartCount.get(),
			"동시에 들어온 두 요청의 dense 조회가 서로를 기다리지 않고 겹쳐서 시작해야 한다. 겹친 횟수: "
				+ overlappedStartCount.get());
	}

	@Test
	void HYBRID_T6_dense와_sparse가_모두_공통_timeout에_근접하면_전체_대기시간이_budget_하나에_수렴한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenAnswer(invocation -> {
			Thread.sleep(300);
			return List.of(new DenseCandidateSource.Candidate(1L, 0.9));
		});
		when(sparseCandidateSource.findCandidates(anyString())).thenAnswer(invocation -> {
			Thread.sleep(300);
			return List.of(new DenseCandidateSource.Candidate(2L, 5.0));
		});
		GameSummary lexicalMatch = new GameSummary(41L, 1041L, "budget 폴백 게임");
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(lexicalMatch), PageRequest.of(0, 10), false));
		long hybridTimeoutMillis = 100;

		long startedAtNanos = System.nanoTime();
		SemanticGameSearchResult result = service(gameRepository, candidateSource, sparseCandidateSource,
			java.time.Duration.ofMillis(hybridTimeoutMillis)).search(query("전략 게임"));
		long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
		assertEquals(List.of(lexicalMatch.id()), result.content().stream().map(GameSummary::id).toList());
		assertTrue(elapsedMillis < hybridTimeoutMillis * 3 / 2,
			"총 대기시간이 공통 timeout budget 하나에 수렴해야 한다. 실제: " + elapsedMillis + "ms (budget: "
				+ hybridTimeoutMillis + "ms)");
	}

	@Test
	void HYBRID_T7_dense_후보_조회에서_런타임_예외가_아닌_예외가_발생하면_IllegalStateException으로_감싸_전파한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource candidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		when(candidateSource.findCandidates(anyString())).thenAnswer(invocation -> {
			throw new java.io.IOException("candidate 원격 호출 checked 실패");
		});

		assertThrows(IllegalStateException.class,
			() -> service(gameRepository, candidateSource).search(query("전략 게임")));
	}

	@Test
	void ISSUE_1001_T2_sparseExecutor는_포화된_작업을_즉시_거부한다() throws Exception {
		SemanticGameSearchService service = service(org.mockito.Mockito.mock(GameRepository.class),
			org.mockito.Mockito.mock(DenseCandidateSource.class),
			org.mockito.Mockito.mock(SparseCandidateSource.class));
		CountDownLatch started = new CountDownLatch(sparseExecutor(service).getMaximumPoolSize());
		CountDownLatch release = new CountDownLatch(1);
		try {
			occupySparseWorkers(service, started, release);
			assertTrue(started.await(2, TimeUnit.SECONDS), "모든 sparse worker가 점유되어야 합니다.");

			assertThrows(RejectedExecutionException.class, () -> sparseExecutor(service).submit(() -> {}),
				"포화된 sparse 작업은 무한 대기열에 쌓이지 않고 즉시 거부되어야 합니다.");
		} finally {
			release.countDown();
			service.shutdown();
		}
	}

	@Test
	void ISSUE_1001_T3_interrupt를_무시하는_worker가_점유돼도_후속_요청은_대기열없이_lexicalFallback으로_수렴한다()
		throws Exception {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource denseCandidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		when(denseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(sparseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findLexicalFallbackSummaries(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 10), false));
		SemanticGameSearchService service = service(gameRepository, denseCandidateSource, sparseCandidateSource,
			Duration.ofMillis(150));
		CountDownLatch started = new CountDownLatch(sparseExecutor(service).getMaximumPoolSize());
		CountDownLatch release = new CountDownLatch(1);
		try {
			occupySparseWorkers(service, started, release);
			assertTrue(started.await(2, TimeUnit.SECONDS), "모든 sparse worker가 점유되어야 합니다.");

			long startedAtNanos = System.nanoTime();
			SemanticGameSearchResult result = service.search(query("후속 요청"));
			long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

			assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
			assertTrue(elapsedMillis < 500, "후속 요청은 timeout budget 안에서 fallback으로 응답해야 합니다.");
			assertEquals(0, sparseExecutor(service).getQueue().size(), "거부된 sparse 작업은 대기열에 남지 않아야 합니다.");
		} finally {
			release.countDown();
			service.shutdown();
		}
	}

	@Test
	void ISSUE_1001_T4_worker가_해제된_뒤_남은_deadline으로_sparse_작업을_다시_처리한다() throws Exception {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource denseCandidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		AtomicReference<Duration> observedTimeout = new AtomicReference<>();
		SparseCandidateSource sparseCandidateSource = deadlineAwareSparseCandidateSource((rawQuery, timeout) -> {
			observedTimeout.set(timeout);
			return List.of(new DenseCandidateSource.Candidate(1001L, 1.0));
		});
		Game sparseGame = game(1001L, 10_001L, "복구된 sparse 게임");
		when(denseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(sparseGame));
		SemanticGameSearchService service = service(gameRepository, denseCandidateSource, sparseCandidateSource,
			Duration.ofSeconds(1));
		CountDownLatch started = new CountDownLatch(sparseExecutor(service).getMaximumPoolSize());
		CountDownLatch release = new CountDownLatch(1);
		try {
			occupySparseWorkers(service, started, release);
			assertTrue(started.await(2, TimeUnit.SECONDS), "모든 sparse worker가 점유되어야 합니다.");
			release.countDown();
			awaitSparseWorkersToBecomeIdle(sparseExecutor(service));

			SemanticGameSearchResult result = service.search(query("복구 요청"));

			assertEquals(SemanticGameSearchMode.SPARSE_FALLBACK, result.mode());
			assertEquals(List.of(sparseGame.getId()), result.content().stream().map(GameSummary::id).toList());
			assertNotNull(observedTimeout.get(), "복구된 sparse 작업에도 남은 공통 deadline이 전달되어야 합니다.");
			assertTrue(observedTimeout.get().isPositive());
		} finally {
			release.countDown();
			service.shutdown();
		}
	}

	@Test
	void ISSUE_1001_T5_정상_dense와_sparse_결과를_기존_SEMANTIC_경로로_결합한다() {
		GameRepository gameRepository = org.mockito.Mockito.mock(GameRepository.class);
		DenseCandidateSource denseCandidateSource = org.mockito.Mockito.mock(DenseCandidateSource.class);
		AtomicReference<Duration> observedTimeout = new AtomicReference<>();
		SparseCandidateSource sparseCandidateSource = deadlineAwareSparseCandidateSource((rawQuery, timeout) -> {
			observedTimeout.set(timeout);
			return List.of(new DenseCandidateSource.Candidate(1002L, 1.0));
		});
		Game denseGame = game(1001L, 10_001L, "Dense 게임");
		Game sparseGame = game(1002L, 10_002L, "Sparse 게임");
		when(denseCandidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(denseGame.getId(), 1.0)));
		when(gameRepository.findAll(any(Specification.class))).thenReturn(List.of(denseGame, sparseGame));

		SemanticGameSearchResult result = service(gameRepository, denseCandidateSource, sparseCandidateSource)
			.search(query("정상 결합"));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(denseGame.getId(), sparseGame.getId()),
			result.content().stream().map(GameSummary::id).toList());
		assertNotNull(observedTimeout.get(), "정상 sparse 경로에도 남은 공통 deadline이 전달되어야 합니다.");
		assertTrue(observedTimeout.get().isPositive());
	}

	private SemanticGameSearchService service(
		GameRepository gameRepository, DenseCandidateSource candidateSource) {
		SparseCandidateSource sparseCandidateSource = org.mockito.Mockito.mock(SparseCandidateSource.class);
		when(sparseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
		return service(gameRepository, candidateSource, sparseCandidateSource);
	}

	private ThreadPoolExecutor sparseExecutor(SemanticGameSearchService service) {
		return (ThreadPoolExecutor)ReflectionTestUtils.getField(service, "sparseExecutor");
	}

	private void occupySparseWorkers(SemanticGameSearchService service, CountDownLatch started,
		CountDownLatch release) {
		for (int index = 0; index < sparseExecutor(service).getMaximumPoolSize(); index++) {
			sparseExecutor(service).submit(() -> {
				started.countDown();
				awaitIgnoringInterrupts(release);
			});
		}
	}

	private void awaitIgnoringInterrupts(CountDownLatch release) {
		boolean interrupted = false;
		while (release.getCount() > 0) {
			try {
				release.await();
			} catch (InterruptedException exception) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private void awaitSparseWorkersToBecomeIdle(ThreadPoolExecutor executor) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (executor.getActiveCount() > 0 && System.nanoTime() < deadlineNanos) {
			Thread.yield();
		}
		assertEquals(0, executor.getActiveCount(), "worker가 해제된 뒤 executor가 idle 상태가 되어야 합니다.");
	}

	private SparseCandidateSource deadlineAwareSparseCandidateSource(
		java.util.function.BiFunction<String, Duration, List<DenseCandidateSource.Candidate>> finder) {
		try {
			Class<?> deadlineAwareType = Class.forName(SparseCandidateSource.class.getName() + "$DeadlineAware");
			return (SparseCandidateSource)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] {deadlineAwareType}, (proxy, method, arguments) -> {
					if (method.getName().equals("findCandidates") && arguments.length == 2) {
						return finder.apply((String)arguments[0], (Duration)arguments[1]);
					}
					throw new AssertionError("sparse 후보 조회는 deadline-aware 계약을 사용해야 합니다.");
				});
		} catch (ClassNotFoundException exception) {
			throw new AssertionError("sparse 후보 조회에 남은 deadline을 전달하는 계약이 없습니다.", exception);
		}
	}

	private SemanticGameSearchService service(
		GameRepository gameRepository, DenseCandidateSource candidateSource,
		SparseCandidateSource sparseCandidateSource) {
		return service(gameRepository, candidateSource, sparseCandidateSource, java.time.Duration.ofSeconds(6));
	}

	private SemanticGameSearchService service(
		GameRepository gameRepository, DenseCandidateSource candidateSource,
		SparseCandidateSource sparseCandidateSource, java.time.Duration hybridCandidateTimeout) {
		return new SemanticGameSearchService(gameRepository, candidateSource, sparseCandidateSource,
			hybridCandidateTimeout);
	}

	private SemanticGameSearchQuery query(String rawQuery) {
		return query(rawQuery, 0, 10);
	}

	private SemanticGameSearchQuery query(String rawQuery, int page, int size) {
		return new SemanticGameSearchQuery(rawQuery, GameListSearchCriteria.from(new GameListRequest()), page, size);
	}

	private Game game(long id, long bggId, String name) {
		Game game = new Game(bggId, name, name, "2~4명", "전략", "30분", "설명", "상세 설명");
		ReflectionTestUtils.setField(game, "id", id);
		ReflectionTestUtils.setField(game, "popularityScore", BigDecimal.ZERO);
		return game;
	}
}
