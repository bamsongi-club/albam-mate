package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameDetail;
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
class GameQueryServiceDetailTest {

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
	void 게임_상세는_전체_게임_필드와_예정_모임_수를_매핑한다() {
		Game game = mock(Game.class);
		when(game.getId()).thenReturn(1L);
		when(game.getBggId()).thenReturn(1001L);
		when(game.getName()).thenReturn("카탄");
		when(game.getEnglishName()).thenReturn("Catan");
		when(game.getAlias()).thenReturn("카탄 기본판");
		when(game.getImageUrl()).thenReturn("https://example.com/catan.jpg");
		when(game.getSupportedPlayerCount()).thenReturn("3~4명");
		when(game.getTag()).thenReturn("전략");
		when(game.getEstimatedPlayTime()).thenReturn("60~90분");
		when(game.getComplexity()).thenReturn(new BigDecimal("2.00"));
		when(game.getReleaseYear()).thenReturn(1995);
		when(game.getDescription()).thenReturn("간단한 게임 설명");
		when(game.getDetailDescription()).thenReturn("상세한 게임 설명");
		when(gameRepository.findById(1L)).thenReturn(Optional.of(game));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW))
			.thenReturn(Map.of(1L, 2L));

		GameDetail result = gameQueryService.findById(1L);

		assertEquals(
			new GameDetail(
				1L,
				1001L,
				"카탄",
				"Catan",
				"https://example.com/catan.jpg",
				"3~4명",
				"전략",
				"60~90분",
				new BigDecimal("2.00"),
				1995,
				2L,
				"카탄 기본판",
				"간단한 게임 설명",
				"상세한 게임 설명",
				null),
			result);
		verify(gameRepository).findById(1L);
		verify(upcomingRoomCountQuery).findUpcomingRoomCounts(List.of(1L), NOW);
	}

	@Test
	void 없는_게임_ID는_GAME_NOT_FOUND다() {
		when(gameRepository.findById(999L)).thenReturn(Optional.empty());

		BusinessException exception = assertThrows(BusinessException.class,
			() -> gameQueryService.findById(999L));

		assertEquals(ErrorCode.GAME_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(upcomingRoomCountQuery);
	}
}
