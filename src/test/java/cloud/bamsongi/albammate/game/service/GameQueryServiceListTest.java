package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameCategoryRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GamePlayerPreferenceRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRelationRepository;
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

	@Mock
	private GameCategoryRelationRepository gameCategoryRelationRepository;

	@Mock
	private GameThemeRelationRepository gameThemeRelationRepository;

	@Mock
	private GamePlayerPreferenceRepository gamePlayerPreferenceRepository;

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
			gameThemeRepository,
			gameCategoryRelationRepository,
			gameThemeRelationRepository,
			gamePlayerPreferenceRepository);
	}

	@Test
	void 모든_조건을_불변_검색_조건으로_묶어_단일_저장소_조회에_전달한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		GameListRequest request = request(
			"  카탄  ", false, 4, List.of(GamePlayTimeFilter.OVER_30_TO_60), "2.00", "3.00");
		Game game = game(1L, "카탄");
		when(gameRepository.findAll(any(Specification.class), eq(pageable)))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 1));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of(1L, 2L));

		Page<GameListItem> result = gameQueryService.findPage(request);

		verify(gameRepository).findAll(any(Specification.class), eq(pageable));
		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
	}

	@Test
	void 존재하지_않는_테마_코드는_검증오류로_거절한다() {
		GameListRequest request = new GameListRequest();
		request.setTheme(List.of("UNKNOWN_THEME"));
		when(gameThemeRepository.countByCodeIn(List.of("UNKNOWN_THEME"))).thenReturn(0L);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> gameQueryService.findPage(request));

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
		when(gameRepository.findAll(any(Specification.class), eq(pageable)))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 2));

		Page<GameListItem> result = gameQueryService.findPage(request);

		verify(gameRepository).findAll(any(Specification.class), eq(pageable));
		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
	}

	@Test
	void 예정_모임_게임이_없으면_저장소_조회없이_요청_페이지_기준_빈_결과를_반환한다() {
		GameListRequest request = request(null, true, null, null, null, null);
		request.setPage(2);
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(NOW)).thenReturn(Map.of());

		Page<GameListItem> result = gameQueryService.findPage(request);

		assertEquals(0, result.getTotalElements());
		assertEquals(2, result.getNumber());
		verify(upcomingRoomCountQuery).findUpcomingRoomCounts(NOW);
		verifyNoInteractions(gameRepository);
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

	private Game game(Long id, String name) {
		Game game = new Game(1001L, name, "Catan", "3~4명", "전략", "60~90분", "설명", "상세 설명");
		ReflectionTestUtils.setField(game, "id", id);
		return game;
	}

	private Pageable fixedPageRequest(int page, int size) {
		return PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
	}
}
