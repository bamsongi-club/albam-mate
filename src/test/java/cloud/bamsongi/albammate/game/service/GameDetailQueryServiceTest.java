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

import com.jayway.jsonpath.JsonPath;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameDetailFixture;
import cloud.bamsongi.albammate.game.repository.GameCategoryRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRelationRepository;
import cloud.bamsongi.albammate.game.repository.GamePlayerPreferenceRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRelationRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class GameDetailQueryServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

	@Mock
	private GameRepository gameRepository;

	@Mock
	private UpcomingRoomCountQuery upcomingRoomCountQuery;

	@Mock
	private UserPlayedGameRepository userPlayedGameRepository;

	@Mock
	private GameCategoryRelationRepository gameCategoryRelationRepository;

	@Mock
	private GameMechanismRelationRepository gameMechanismRelationRepository;

	@Mock
	private GameThemeRelationRepository gameThemeRelationRepository;

	@Mock
	private GamePlayerPreferenceRepository gamePlayerPreferenceRepository;

	private GameDetailQueryService gameDetailQueryService;

	@BeforeEach
	void setUp() {
		gameDetailQueryService = new GameDetailQueryService(
			gameRepository,
			Clock.fixed(NOW, ZoneOffset.UTC),
			upcomingRoomCountQuery,
			userPlayedGameRepository,
			gameCategoryRelationRepository,
			gameMechanismRelationRepository,
			gameThemeRelationRepository,
			gamePlayerPreferenceRepository);
	}

	@Test
	void 게임_상세는_메커니즘_관계가_없으면_빈_배열을_반환하며_기존_필드를_유지한다() throws Exception {
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
		when(game.getMinAge()).thenReturn(null);
		when(game.getDescription()).thenReturn("간단한 게임 설명");
		when(game.getDetailDescription()).thenReturn("상세한 게임 설명");
		when(gameRepository.findById(1L)).thenReturn(Optional.of(game));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW))
			.thenReturn(Map.of(1L, 2L));

		GameDetail result = gameDetailQueryService.findById(1L);

		assertEquals(
			GameDetailFixture.of(
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
		assertEquals(List.of(), JsonPath.read(new ObjectMapper().writeValueAsString(result), "$.mechanisms"));
	}

	@Test
	void 없는_게임_ID는_GAME_NOT_FOUND다() {
		when(gameRepository.findById(999L)).thenReturn(Optional.empty());

		BusinessException exception = assertThrows(BusinessException.class,
			() -> gameDetailQueryService.findById(999L));

		assertEquals(ErrorCode.GAME_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(upcomingRoomCountQuery);
	}
}
