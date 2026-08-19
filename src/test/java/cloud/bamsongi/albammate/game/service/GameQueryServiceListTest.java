package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class GameQueryServiceListTest {

	private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

	@Mock
	private GameRepository gameRepository;

	@Mock
	private UpcomingRoomCountQuery upcomingRoomCountQuery;

	@Mock
	private GameMechanismRepository gameMechanismRepository;

	@Mock
	private UserPlayedGameRepository userPlayedGameRepository;

	@Mock
	private GameCategoryRepository gameCategoryRepository;

	@Mock
	private GameThemeRepository gameThemeRepository;

	private GameQueryService gameQueryService;

	@BeforeEach
	void setUp() {
		gameQueryService = new GameQueryService(
			gameRepository,
			Clock.fixed(NOW, ZoneOffset.UTC),
			upcomingRoomCountQuery,
			gameMechanismRepository,
			userPlayedGameRepository,
			gameCategoryRepository,
			gameThemeRepository);
	}

	@Test
	void 모든_조건을_불변_검색_조건으로_묶어_단일_저장소_조회에_전달한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		GameListRequest request = request(
			"  카탄  ", false, 4, List.of(GamePlayTimeFilter.OVER_30_TO_60), "2.00", "3.00");
		Game game = game(1L, "카탄");
		when(gameRepository.findBy(any(Specification.class), any()))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 1));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of(1L, 2L));

		Slice<GameListItem> result = gameQueryService.findPage(request, null);

		verify(gameRepository).findBy(any(Specification.class), any());
		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
	}

	@Test
	void 동적_게임목록은_size_plus_one_Slice_조회로_다음페이지를_판정한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		Game game = game(1L, "카탄");
		when(gameRepository.findBy(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(game), pageable, true));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of(1L, 2L));

		var result = gameQueryService.findPage(request(null, false, null, null, null, null), null);

		verify(gameRepository).findBy(any(Specification.class), any());
		assertEquals(true, result.hasNext());
	}

	@Test
	void 측정_runner가_호출할_기본게임목록도_count없는_Slice_경로를_사용한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		when(gameRepository.findBy(any(Specification.class), any()))
			.thenReturn(new SliceImpl<>(List.of(), pageable, false));

		var result = gameQueryService.findPage(new GameListRequest(), null);

		verify(gameRepository).findBy(any(Specification.class), any());
		assertEquals(false, result.hasNext());
	}

	@Test
	void 존재하지_않는_테마_코드는_검증오류로_거절한다() {
		GameListRequest request = new GameListRequest();
		request.setTheme(List.of("UNKNOWN_THEME"));
		when(gameThemeRepository.countByCodeIn(List.of("UNKNOWN_THEME"))).thenReturn(0L);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> gameQueryService.findPage(request, null));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
		verify(gameThemeRepository).countByCodeIn(List.of("UNKNOWN_THEME"));
		verifyNoInteractions(gameRepository);
	}

	@Test
	void 메커니즘_카테고리_테마_순서로_검증하고_앞단계_실패면_후속_저장소를_호출하지_않는다() {
		GameListRequest validRequest = filterRequest(
			Arrays.asList(null, "DICE"), Arrays.asList(null, "STRATEGY"), Arrays.asList(null, "ANIMALS"));
		Pageable pageable = fixedPageRequest(0, 10);
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("DICE"))).thenReturn(1L);
		when(gameCategoryRepository.countByCodeIn(List.of("STRATEGY"))).thenReturn(1L);
		when(gameThemeRepository.countByCodeIn(List.of("ANIMALS"))).thenReturn(1L);
		when(gameRepository.findBy(any(Specification.class), any())).thenReturn(Page.empty(pageable));

		gameQueryService.findPage(validRequest, null);

		InOrder validationOrder = inOrder(
			gameMechanismRepository, gameCategoryRepository, gameThemeRepository);
		validationOrder.verify(gameMechanismRepository).countByCodeInAndIsPublicTrue(List.of("DICE"));
		validationOrder.verify(gameCategoryRepository).countByCodeIn(List.of("STRATEGY"));
		validationOrder.verify(gameThemeRepository).countByCodeIn(List.of("ANIMALS"));

		clearInvocations(gameMechanismRepository, gameCategoryRepository, gameThemeRepository);
		GameListRequest invalidMechanismRequest = filterRequest(
			List.of("PRIVATE"), List.of("STRATEGY"), List.of("ANIMALS"));
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("PRIVATE"))).thenReturn(0L);

		assertThrows(BusinessException.class, () -> gameQueryService.findPage(invalidMechanismRequest, null));

		verify(gameMechanismRepository).countByCodeInAndIsPublicTrue(List.of("PRIVATE"));
		verifyNoInteractions(gameCategoryRepository, gameThemeRepository);

		clearInvocations(gameMechanismRepository, gameCategoryRepository, gameThemeRepository);
		GameListRequest invalidCategoryRequest = filterRequest(
			List.of("DICE"), List.of("UNKNOWN_CATEGORY"), List.of("ANIMALS"));
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("DICE"))).thenReturn(1L);
		when(gameCategoryRepository.countByCodeIn(List.of("UNKNOWN_CATEGORY"))).thenReturn(0L);

		assertThrows(BusinessException.class, () -> gameQueryService.findPage(invalidCategoryRequest, null));

		verify(gameMechanismRepository).countByCodeInAndIsPublicTrue(List.of("DICE"));
		verify(gameCategoryRepository).countByCodeIn(List.of("UNKNOWN_CATEGORY"));
		verifyNoInteractions(gameThemeRepository);
	}

	@Test
	void 필터_코드의_null_빈_목록_중복을_정규화해_저장소에_전달한다() {
		GameListRequest request = filterRequest(
			Arrays.asList(null, "DICE", "DICE"), List.of(), Arrays.asList(null, "ANIMALS", "ANIMALS"));
		Pageable pageable = fixedPageRequest(0, 10);
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("DICE"))).thenReturn(1L);
		when(gameThemeRepository.countByCodeIn(List.of("ANIMALS"))).thenReturn(1L);
		when(gameRepository.findBy(any(Specification.class), any())).thenReturn(Page.empty(pageable));

		gameQueryService.findPage(request, null);

		verify(gameMechanismRepository).countByCodeInAndIsPublicTrue(List.of("DICE"));
		verifyNoInteractions(gameCategoryRepository);
		verify(gameThemeRepository).countByCodeIn(List.of("ANIMALS"));
	}

	@Test
	void 비공개_메커니즘과_미존재_카테고리_테마를_검증오류로_거절한다() {
		GameListRequest mechanismRequest = filterRequest(Arrays.asList(null, "PRIVATE"), List.of(), List.of());
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("PRIVATE"))).thenReturn(0L);

		BusinessException mechanismException = assertThrows(BusinessException.class,
			() -> gameQueryService.findPage(mechanismRequest, null));

		assertEquals(ErrorCode.VALIDATION_ERROR, mechanismException.getErrorCode());
		verify(gameMechanismRepository).countByCodeInAndIsPublicTrue(List.of("PRIVATE"));

		clearInvocations(gameMechanismRepository, gameCategoryRepository, gameThemeRepository);
		GameListRequest categoryRequest = filterRequest(List.of(), Arrays.asList(null, "UNKNOWN_CATEGORY"), List.of());
		when(gameCategoryRepository.countByCodeIn(List.of("UNKNOWN_CATEGORY"))).thenReturn(0L);

		BusinessException categoryException = assertThrows(BusinessException.class,
			() -> gameQueryService.findPage(categoryRequest, null));

		assertEquals(ErrorCode.VALIDATION_ERROR, categoryException.getErrorCode());
		verify(gameCategoryRepository).countByCodeIn(List.of("UNKNOWN_CATEGORY"));

		clearInvocations(gameMechanismRepository, gameCategoryRepository, gameThemeRepository);
		GameListRequest themeRequest = filterRequest(List.of(), List.of(), Arrays.asList(null, "UNKNOWN_THEME"));
		when(gameThemeRepository.countByCodeIn(List.of("UNKNOWN_THEME"))).thenReturn(0L);

		BusinessException themeException = assertThrows(BusinessException.class,
			() -> gameQueryService.findPage(themeRequest, null));

		assertEquals(ErrorCode.VALIDATION_ERROR, themeException.getErrorCode());
		verify(gameThemeRepository).countByCodeIn(List.of("UNKNOWN_THEME"));
	}

	@Test
	void 유효한_필터의_검색결과와_검증오류_계약을_유지한다() {
		GameListRequest validRequest = filterRequest(
			Arrays.asList(null, "DICE", "DICE"), List.of("STRATEGY", "STRATEGY"), List.of("ANIMALS"));
		Pageable pageable = fixedPageRequest(0, 10);
		Game game = game(1L, "카탄");
		when(gameMechanismRepository.countByCodeInAndIsPublicTrue(List.of("DICE"))).thenReturn(1L);
		when(gameCategoryRepository.countByCodeIn(List.of("STRATEGY"))).thenReturn(1L);
		when(gameThemeRepository.countByCodeIn(List.of("ANIMALS"))).thenReturn(1L);
		when(gameRepository.findBy(any(Specification.class), any()))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 1));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of(1L, 2L));

		Slice<GameListItem> result = gameQueryService.findPage(validRequest, null);

		assertEquals(false, result.hasNext());
		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
		verify(gameRepository).findBy(any(Specification.class), any());

		clearInvocations(gameMechanismRepository, gameCategoryRepository, gameThemeRepository, gameRepository);
		GameListRequest invalidRequest = filterRequest(List.of(), List.of(), Arrays.asList(null, "UNKNOWN_THEME"));
		when(gameThemeRepository.countByCodeIn(List.of("UNKNOWN_THEME"))).thenReturn(0L);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> gameQueryService.findPage(invalidRequest, null));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
		verify(gameThemeRepository).countByCodeIn(List.of("UNKNOWN_THEME"));
		verifyNoInteractions(gameRepository);
	}

	@Test
	void 예정_모임_필터는_기존_집계_계약의_게임_ID를_같은_검색_조건에_전달한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		GameListRequest request = request(null, true, null, null, null, null);
		Game game = game(1L, "카탄");
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(NOW)).thenReturn(Map.of(1L, 2L, 2L, 1L));
		when(gameRepository.findBy(any(Specification.class), any()))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 2));

		Slice<GameListItem> result = gameQueryService.findPage(request, null);

		verify(gameRepository).findBy(any(Specification.class), any());
		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
	}

	@Test
	void 예정_모임_게임이_없으면_저장소_조회없이_요청_페이지_기준_빈_결과를_반환한다() {
		GameListRequest request = request(null, true, null, null, null, null);
		request.setPage(2);
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(NOW)).thenReturn(Map.of());

		Slice<GameListItem> result = gameQueryService.findPage(request, null);

		assertEquals(false, result.hasNext());
		assertEquals(2, result.getNumber());
		verify(upcomingRoomCountQuery).findUpcomingRoomCounts(NOW);
		verifyNoInteractions(gameRepository);
	}

	@Test
	void 요청_페이지가_범위를_넘어_content만_비면_페이지_메타데이터를_보존하고_보조_조회를_건너뛴다() {
		GameListRequest request = request(null, false, null, null, null, null);
		request.setPage(3);
		request.setSize(20);
		Pageable pageable = fixedPageRequest(3, 20);
		when(gameRepository.findBy(any(Specification.class), any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 7));

		Slice<GameListItem> result = gameQueryService.findPage(request, 42L);

		assertEquals(List.of(), result.getContent());
		assertEquals(3, result.getNumber());
		assertEquals(20, result.getSize());
		assertEquals(false, result.hasNext());
		verifyNoInteractions(upcomingRoomCountQuery, userPlayedGameRepository);
	}

	@Test
	void 예정_모임_필터와_페이지_결과_집계에_요청_시작_기준_시각을_사용한다() {
		RequestStartClock requestClock = new RequestStartClock(NOW);
		gameQueryService = newGameQueryService(requestClock);
		GameListRequest request = request(null, true, null, null, null, null);
		request.setCategory(List.of("STRATEGY"));
		Game game = game(1L, "카탄");
		Pageable pageable = fixedPageRequest(0, 10);
		when(gameCategoryRepository.countByCodeIn(List.of("STRATEGY"))).thenAnswer(invocation -> {
			requestClock.closeRequestStart();
			return 1L;
		});
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(NOW)).thenReturn(Map.of(1L, 2L));
		when(gameRepository.findBy(any(Specification.class), any()))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 1));

		Slice<GameListItem> result = gameQueryService.findPage(request, null);

		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
		verify(upcomingRoomCountQuery).findUpcomingRoomCounts(NOW);
	}

	@Test
	void 목록_조회는_기준_시각을_재호출하지_않는다() {
		RequestStartClock requestClock = new RequestStartClock(NOW);
		gameQueryService = newGameQueryService(requestClock);
		GameListRequest request = request(null, false, null, null, null, null);
		Game game = game(1L, "카탄");
		Pageable pageable = fixedPageRequest(0, 10);
		when(gameRepository.findBy(any(Specification.class), any())).thenAnswer(invocation -> {
			requestClock.closeRequestStart();
			return new PageImpl<>(List.of(game), pageable, 1);
		});
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of(1L, 2L));

		Slice<GameListItem> result = gameQueryService.findPage(request, null);

		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
		verify(upcomingRoomCountQuery).findUpcomingRoomCounts(List.of(1L), NOW);
	}

	private GameListRequest request(
		String keyword,
		boolean upcomingOnly,
		Integer playerCount,
		List<GamePlayTimeFilter> playTime,
		String complexityMin,
		String complexityMax) {
		GameListRequest request = new GameListRequest();
		request.setKeyword(keyword);
		request.setUpcomingOnly(upcomingOnly);
		request.setPlayerCount(playerCount);
		request.setPlayTime(playTime);
		request.setComplexityMin(
			complexityMin == null ? null : new java.math.BigDecimal(complexityMin));
		request.setComplexityMax(
			complexityMax == null ? null : new java.math.BigDecimal(complexityMax));
		return request;
	}

	private GameListRequest filterRequest(List<String> mechanism, List<String> category, List<String> theme) {
		GameListRequest request = new GameListRequest();
		request.setMechanism(mechanism);
		request.setCategory(category);
		request.setTheme(theme);
		return request;
	}

	private Game game(Long id, String name) {
		Game game = new Game(1001L, name, "Catan", "3~4명", "전략", "60~90분", "설명", "상세 설명");
		ReflectionTestUtils.setField(game, "id", id);
		return game;
	}

	private Pageable fixedPageRequest(int page, int size) {
		return PageRequest.of(
			page,
			size,
			Sort.by(
				Sort.Order.desc("popularityScore"),
				Sort.Order.asc("name"),
				Sort.Order.asc("id")));
	}

	private GameQueryService newGameQueryService(Clock clock) {
		return new GameQueryService(
			gameRepository,
			clock,
			upcomingRoomCountQuery,
			gameMechanismRepository,
			userPlayedGameRepository,
			gameCategoryRepository,
			gameThemeRepository);
	}

	private static final class RequestStartClock extends Clock {

		private final Instant requestStart;
		private boolean requestStartOpen = true;
		private boolean read;

		private RequestStartClock(Instant requestStart) {
			this.requestStart = requestStart;
		}

		void closeRequestStart() {
			requestStartOpen = false;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			if (!requestStartOpen || read) {
				throw new AssertionError("목록 요청 시작 시각은 한 번만 읽어야 합니다.");
			}
			read = true;
			return requestStart;
		}
	}
}
