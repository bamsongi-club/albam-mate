package cloud.bamsongi.albammate.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameMechanism;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.entity.GameTheme;
import cloud.bamsongi.albammate.game.entity.GameThemeRelation;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameMechanismRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GameMatchModeHttpIntegrationTest {

	private static final String PREFIX = "GameMatchModeHttp-";
	private static final Instant REVIEWED_AT = Instant.parse("2026-08-11T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private GameRepository gameRepository;
	@Autowired
	private GameThemeRepository gameThemeRepository;
	@Autowired
	private GameThemeRelationRepository gameThemeRelationRepository;
	@Autowired
	private GameMechanismRepository gameMechanismRepository;
	@Autowired
	private GameMechanismRelationRepository gameMechanismRelationRepository;

	@Test
	void 테마와_메커니즘_모드를_생략하면_각각_ANY이고_한_그룹의_모드는_다른_그룹에_영향을주지않는다() throws Exception {
		GameTheme fantasy = saveTheme(5101L, "FANTASY");
		GameTheme war = saveTheme(5102L, "WAR");
		GameMechanism hand = saveMechanism(5101L, "HAND_MANAGEMENT");
		GameMechanism dice = saveMechanism(5102L, "DICE_ROLLING");
		Game both = saveGame(510001L, "T1 both", 2);
		Game themeAllMechanismAny = saveGame(510002L, "T1 theme all", 2);
		Game themeAnyMechanismAll = saveGame(510003L, "T1 mechanism all", 2);
		linkTheme(both, fantasy);
		linkTheme(both, war);
		linkMechanism(both, hand);
		linkMechanism(both, dice);
		linkTheme(themeAllMechanismAny, fantasy);
		linkTheme(themeAllMechanismAny, war);
		linkMechanism(themeAllMechanismAny, hand);
		linkTheme(themeAnyMechanismAll, fantasy);
		linkMechanism(themeAnyMechanismAll, hand);
		linkMechanism(themeAnyMechanismAll, dice);

		mockMvc.perform(matchRequest("T1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(3));
		mockMvc.perform(matchRequest("T1").param("themeMatch", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(2));
		mockMvc.perform(matchRequest("T1").param("mechanismMatch", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(2));
	}

	@Test
	void 메커니즘_ANY는_하나이상_ALL은_모든_고유코드관계를_요구하고_목록_항목이_같다() throws Exception {
		GameMechanism hand = saveMechanism(5201L, "HAND_MANAGEMENT");
		GameMechanism dice = saveMechanism(5202L, "DICE_ROLLING");
		Game both = saveGame(520001L, "T2 both", 2);
		Game handOnly = saveGame(520002L, "T2 hand", 2);
		Game diceOnly = saveGame(520003L, "T2 dice", 2);
		linkMechanism(both, hand);
		linkMechanism(both, dice);
		linkMechanism(handOnly, hand);
		linkMechanism(diceOnly, dice);

		mockMvc.perform(
			get("/api/games")
				.param("keyword", PREFIX + "T2")
				.param("mechanism", "HAND_MANAGEMENT")
				.param("mechanism", "DICE_ROLLING")
				.param("mechanism", "DICE_ROLLING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").doesNotExist())
			.andExpect(jsonPath("$.data.totalPages").doesNotExist())
			.andExpect(jsonPath("$.data.content.length()").value(3));
		mockMvc.perform(
			get("/api/games")
				.param("keyword", PREFIX + "T2")
				.param("mechanism", "HAND_MANAGEMENT")
				.param("mechanism", "DICE_ROLLING")
				.param("mechanism", "DICE_ROLLING")
				.param("mechanismMatch", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(both.getId()));
	}

	@Test
	void 테마와_메커니즘_모드는_각각_단일_ANY_ALL만_허용하고_잘못된값과_중복을_검증오류로_거절한다() throws Exception {
		mockMvc.perform(get("/api/games").param("themeMatch", "ANY").param("themeMatch", "ALL"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mockMvc.perform(get("/api/games").param("mechanismMatch", "ANY").param("mechanismMatch", "ALL"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mockMvc.perform(get("/api/games").param("mechanismMatch", "INVALID"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mockMvc.perform(get("/api/games").param("mechanism", "UNKNOWN").param("mechanismMatch", "ALL"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void 테마와_메커니즘_모드는_서로독립적이고_다른_검색조건과_AND로_결합한다() throws Exception {
		GameTheme fantasy = saveTheme(5401L, "FANTASY");
		GameTheme war = saveTheme(5402L, "WAR");
		GameMechanism hand = saveMechanism(5401L, "HAND_MANAGEMENT");
		GameMechanism dice = saveMechanism(5402L, "DICE_ROLLING");
		Game matched = saveGame(540001L, "T4 matched", 2);
		Game mechanismAny = saveGame(540002L, "T4 mechanism any", 2);
		Game wrongPlayers = saveGame(540003L, "T4 wrong players", 4);
		for (Game game : new Game[] {matched, mechanismAny, wrongPlayers}) {
			linkTheme(game, fantasy);
			linkTheme(game, war);
		}
		linkMechanism(matched, hand);
		linkMechanism(matched, dice);
		linkMechanism(mechanismAny, hand);
		linkMechanism(wrongPlayers, hand);
		linkMechanism(wrongPlayers, dice);

		mockMvc.perform(
			get("/api/games")
				.param("keyword", PREFIX + "T4")
				.param("theme", "FANTASY")
				.param("theme", "WAR")
				.param("themeMatch", "ALL")
				.param("mechanism", "HAND_MANAGEMENT")
				.param("mechanism", "DICE_ROLLING")
				.param("mechanismMatch", "ALL")
				.param("playerCount", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(matched.getId()));
	}

	@Test
	void V1_관계필터는_ANY_ALL_조합과_Slice_경계를_V0와_같이_보존한다() throws Exception {
		GameTheme fantasy = saveTheme(5501L, "FANTASY");
		GameTheme war = saveTheme(5502L, "WAR");
		GameMechanism hand = saveMechanism(5501L, "HAND_MANAGEMENT");
		GameMechanism dice = saveMechanism(5502L, "DICE_ROLLING");
		Game both = saveGame(550001L, "T5 both", 2);
		Game themeAllMechanismAny = saveGame(550002L, "T5 theme all", 2);
		Game themeAnyMechanismAll = saveGame(550003L, "T5 mechanism all", 2);
		linkTheme(both, fantasy);
		linkTheme(both, war);
		linkMechanism(both, hand);
		linkMechanism(both, dice);
		linkTheme(themeAllMechanismAny, fantasy);
		linkTheme(themeAllMechanismAny, war);
		linkMechanism(themeAllMechanismAny, hand);
		linkTheme(themeAnyMechanismAll, fantasy);
		linkMechanism(themeAnyMechanismAll, hand);
		linkMechanism(themeAnyMechanismAll, dice);

		mockMvc.perform(matchRequest("T5"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10))
			.andExpect(jsonPath("$.data.totalElements").doesNotExist())
			.andExpect(jsonPath("$.data.totalPages").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].id").value(both.getId()))
			.andExpect(jsonPath("$.data.content[1].id").value(themeAnyMechanismAll.getId()))
			.andExpect(jsonPath("$.data.content[2].id").value(themeAllMechanismAny.getId()));
		mockMvc.perform(matchRequest("T5").param("themeMatch", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(both.getId()))
			.andExpect(jsonPath("$.data.content[1].id").value(themeAllMechanismAny.getId()));
		mockMvc.perform(matchRequest("T5").param("mechanismMatch", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(both.getId()))
			.andExpect(jsonPath("$.data.content[1].id").value(themeAnyMechanismAll.getId()));
		mockMvc.perform(matchRequest("T5").param("themeMatch", "ALL").param("mechanismMatch", "ALL"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(both.getId()));
		mockMvc.perform(matchRequest("T5").param("size", "1").param("page", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(both.getId()))
			.andExpect(jsonPath("$.data.hasNext").value(true));
		mockMvc.perform(matchRequest("T5").param("size", "1").param("page", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(1))
			.andExpect(jsonPath("$.data.size").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(themeAnyMechanismAll.getId()))
			.andExpect(jsonPath("$.data.hasNext").value(true));
		mockMvc.perform(matchRequest("T5").param("size", "1").param("page", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(2))
			.andExpect(jsonPath("$.data.size").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(themeAllMechanismAny.getId()))
			.andExpect(jsonPath("$.data.hasNext").value(false));
		mockMvc.perform(get("/api/games").param("playedFilter", "PLAYED_ONLY"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(get("/api/games").param("themeMatch", "ANY").param("themeMatch", "ALL"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder matchRequest(String name) {
		return get("/api/games")
			.param("keyword", PREFIX + name)
			.param("theme", "FANTASY")
			.param("theme", "WAR")
			.param("mechanism", "HAND_MANAGEMENT")
			.param("mechanism", "DICE_ROLLING");
	}

	private GameTheme saveTheme(long bggThemeId, String code) {
		return gameThemeRepository.saveAndFlush(new GameTheme(bggThemeId, code, code + " 한국어", code + " English"));
	}

	private GameMechanism saveMechanism(long bggMechanismId, String code) {
		return gameMechanismRepository.saveAndFlush(
			new GameMechanism(
				bggMechanismId,
				code,
				code + " 한국어",
				code + " English",
				code + " 설명",
				null,
				true,
				"Issue #597",
				"beyejin",
				REVIEWED_AT));
	}

	private Game saveGame(long bggId, String name, int playerCount) {
		Game game = GameFixture.valid(bggId, PREFIX + name);
		ReflectionTestUtils.setField(game, "minPlayers", playerCount);
		ReflectionTestUtils.setField(game, "maxPlayers", playerCount);
		return gameRepository.saveAndFlush(game);
	}

	private void linkTheme(Game game, GameTheme theme) {
		gameThemeRelationRepository.saveAndFlush(new GameThemeRelation(game, theme));
	}

	private void linkMechanism(Game game, GameMechanism mechanism) {
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(game, mechanism));
	}
}
