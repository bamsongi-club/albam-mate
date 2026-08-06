package cloud.bamsongi.albammate.game;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameCategory;
import cloud.bamsongi.albammate.game.entity.GameCategoryRelation;
import cloud.bamsongi.albammate.game.entity.GamePlayerPreference;
import cloud.bamsongi.albammate.game.entity.GameTheme;
import cloud.bamsongi.albammate.game.entity.GameThemeRelation;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameCategoryRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GamePlayerPreferenceRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GameMetadataHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private GameRepository gameRepository;
	@Autowired
	private GameCategoryRepository categoryRepository;
	@Autowired
	private GameThemeRepository themeRepository;
	@Autowired
	private GameCategoryRelationRepository categoryRelationRepository;
	@Autowired
	private GameThemeRelationRepository themeRelationRepository;
	@Autowired
	private GamePlayerPreferenceRepository preferenceRepository;

	@Test
	void 선택지와_카테고리_OR_테마_ANY_ALL_인원_AND_상세_배열을_반환한다() throws Exception {
		GameCategory strategy = categoryRepository
			.saveAndFlush(new GameCategory("STRATEGY", "전략", "Strategy", "strategygames", 1));
		GameCategory family = categoryRepository
			.saveAndFlush(new GameCategory("FAMILY", "가족", "Family", "familygames", 2));
		GameTheme fantasy = themeRepository.saveAndFlush(new GameTheme(1L, "FANTASY", "판타지", "Fantasy"));
		GameTheme war = themeRepository.saveAndFlush(new GameTheme(2L, "WAR", "전쟁", "War"));
		Game both = saveGame(420001L, "Metadata both");
		ReflectionTestUtils.setField(both, "tag", "legacy display tag");
		gameRepository.saveAndFlush(both);
		Game one = saveGame(420002L, "Metadata one");
		Game paged = saveGame(420003L, "Metadata paged");
		categoryRelationRepository.saveAndFlush(new GameCategoryRelation(both, strategy));
		categoryRelationRepository.saveAndFlush(new GameCategoryRelation(one, family));
		categoryRelationRepository.saveAndFlush(new GameCategoryRelation(paged, strategy));
		themeRelationRepository.saveAndFlush(new GameThemeRelation(both, fantasy));
		themeRelationRepository.saveAndFlush(new GameThemeRelation(both, war));
		themeRelationRepository.saveAndFlush(new GameThemeRelation(one, fantasy));
		themeRelationRepository.saveAndFlush(new GameThemeRelation(paged, fantasy));
		preferenceRepository.saveAndFlush(new GamePlayerPreference(both, 4, true, true));
		preferenceRepository.saveAndFlush(new GamePlayerPreference(paged, 4, true, true));

		mockMvc.perform(get("/api/game-categories"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("STRATEGY"))
			.andExpect(jsonPath("$.data[0].id").doesNotExist());
		mockMvc.perform(get("/api/game-themes"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("WAR"))
			.andExpect(jsonPath("$.data[0].bggThemeId").doesNotExist());
		mockMvc
			.perform(get("/api/games").param("keyword", "Metadata").param("category", "STRATEGY").param("category",
				"FAMILY"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(3));
		mockMvc
			.perform(get("/api/games").param("keyword", "Metadata").param("theme", "FANTASY").param("theme", "WAR")
				.param("themeMatch", "ALL"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
		mockMvc
			.perform(get("/api/games").param("keyword", "Metadata").param("category", "STRATEGY")
				.param("theme", "FANTASY").param("playerCount", "2").param("recommendedPlayerCount", "3")
				.param("recommendedPlayerCount", "4").param("bestPlayerCount", "4").param("bestPlayerCount", "5")
				.param("page", "1").param("size", "1"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
			.andExpect(jsonPath("$.data.size").value(1)).andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.totalPages").value(2))
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(paged.getId()));
		mockMvc.perform(get("/api/games/{id}", both.getId()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.categories[0].code").value("STRATEGY"))
			.andExpect(jsonPath("$.data.tag").value("legacy display tag"))
			.andExpect(jsonPath("$.data.categories[0].nameEn").value("Strategy"))
			.andExpect(jsonPath("$.data.themes[0].code").value("WAR"))
			.andExpect(jsonPath("$.data.themes[0].nameEn").value("War"))
			.andExpect(jsonPath("$.data.recommendedPlayerCounts[0]").value(4))
			.andExpect(jsonPath("$.data.bestPlayerCounts[0]").value(4));
	}

	@Test
	void 존재하지않는_코드와_중복_themeMatch를_검증오류로_거절한다() throws Exception {
		mockMvc.perform(get("/api/games").param("category", "UNKNOWN"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mockMvc.perform(get("/api/games").param("themeMatch", "ANY").param("themeMatch", "ALL"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void 카테고리와_테마_선택지는_내부식별자없이_공개필드만_반환한다() throws Exception {
		categoryRepository.saveAndFlush(new GameCategory("STRATEGY", "전략", "Strategy", "strategygames", 1));
		themeRepository.saveAndFlush(new GameTheme(100L, "FANTASY", "판타지", "Fantasy"));
		mockMvc.perform(get("/api/game-categories")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].id").doesNotExist());
		mockMvc.perform(get("/api/game-themes")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data[0].bggThemeId").doesNotExist());
	}

	@Test
	void 중복카테고리는_중복결과를_만들지않고_알수없는코드는_거절한다() throws Exception {
		GameCategory strategy = categoryRepository
			.saveAndFlush(new GameCategory("STRATEGY", "전략", "Strategy", "strategygames", 1));
		Game game = saveGame(420010L, "Duplicate category");
		categoryRelationRepository.saveAndFlush(new GameCategoryRelation(game, strategy));
		mockMvc.perform(get("/api/games").param("category", "STRATEGY").param("category", "STRATEGY"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(game.getId()));
		mockMvc.perform(get("/api/games").param("category", "UNKNOWN").param("category", "UNKNOWN"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void 테마_ANY_ALL과_중복themeMatch_검증을_구분한다() throws Exception {
		GameTheme fantasy = themeRepository.saveAndFlush(new GameTheme(101L, "FANTASY", "판타지", "Fantasy"));
		GameTheme war = themeRepository.saveAndFlush(new GameTheme(102L, "WAR", "전쟁", "War"));
		Game both = saveGame(420011L, "Any all both");
		Game fantasyOnly = saveGame(420012L, "Any all fantasy");
		themeRelationRepository.saveAndFlush(new GameThemeRelation(both, fantasy));
		themeRelationRepository.saveAndFlush(new GameThemeRelation(both, war));
		themeRelationRepository.saveAndFlush(new GameThemeRelation(fantasyOnly, fantasy));
		mockMvc.perform(get("/api/games").param("theme", "FANTASY").param("theme", "WAR"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.content.length()").value(2));
		mockMvc.perform(get("/api/games").param("theme", "FANTASY").param("theme", "WAR").param("themeMatch", "ALL"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(both.getId()));
		mockMvc.perform(get("/api/games").param("themeMatch", "ANY").param("themeMatch", "ALL"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void 관계없는_상세는_메타데이터_빈배열을_반환한다() throws Exception {
		Game game = saveGame(420099L, "Metadata empty");
		mockMvc.perform(get("/api/games/{id}", game.getId())).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.categories.length()").value(0))
			.andExpect(jsonPath("$.data.themes.length()").value(0))
			.andExpect(jsonPath("$.data.recommendedPlayerCounts.length()").value(0))
			.andExpect(jsonPath("$.data.bestPlayerCounts.length()").value(0));
	}

	private Game saveGame(long bggId, String name) {
		Game game = GameFixture.valid(bggId, name);
		ReflectionTestUtils.setField(game, "minPlayers", 2);
		ReflectionTestUtils.setField(game, "maxPlayers", 4);
		return gameRepository.saveAndFlush(game);
	}
}
