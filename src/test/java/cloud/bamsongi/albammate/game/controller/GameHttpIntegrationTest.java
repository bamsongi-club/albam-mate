package cloud.bamsongi.albammate.game.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import cloud.bamsongi.albammate.game.repository.GameRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GameHttpIntegrationTest {

	private static final String SEARCH_PREFIX = "GameHttpIntegrationSearch-";
	private static final String SORT_PREFIX = "GameHttpIntegrationSort-";
	private static final String CARD_PREFIX = "GameHttpIntegrationCard-";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private GameRepository gameRepository;

	@Test
	void 익명_요청은_세션과_CSRF_토큰_없이_실제_보안_필터를_통과해_게임_목록을_조회한다() throws Exception {
		mockMvc.perform(get("/api/games"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200));
	}

	@Test
	void 실제_저장소에서_게임명_부분_검색은_대소문자를_구분하지_않는다() throws Exception {
		Game catan = saveGame(10001L, SEARCH_PREFIX + "Catan", "카탄", "카탄 상세 설명");
		saveGame(10002L, "Azul", "아줄", "아줄 상세 설명");

		mockMvc.perform(get("/api/games").param("keyword", "gamehttpintegrationsearch-cat"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(catan.getId()))
			.andExpect(jsonPath("$.data.content[0].name").value(SEARCH_PREFIX + "Catan"));
	}

	@Test
	void 게임명_검색의_PERCENT는_와일드카드가_아닌_리터럴로_처리된다() throws Exception {
		Game percentGame = saveGame(10003L, SEARCH_PREFIX + "Percent-100%", "퍼센트 게임", "퍼센트 게임 상세 설명");
		saveGame(10004L, SEARCH_PREFIX + "Percent-100X", "대조 게임", "대조 게임 상세 설명");

		mockMvc.perform(get("/api/games").param("keyword", SEARCH_PREFIX + "Percent-100%"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(percentGame.getId()))
			.andExpect(
				jsonPath("$.data.content[0].name").value(SEARCH_PREFIX + "Percent-100%"));
	}

	@Test
	void 게임명_검색의_UNDERSCORE는_와일드카드가_아닌_리터럴로_처리된다() throws Exception {
		Game underscoreGame = saveGame(10005L, SEARCH_PREFIX + "Underscore-A_B", "언더스코어 게임", "언더스코어 게임 상세 설명");
		saveGame(10006L, SEARCH_PREFIX + "Underscore-AXB", "대조 게임", "대조 게임 상세 설명");

		mockMvc.perform(get("/api/games").param("keyword", SEARCH_PREFIX + "Underscore-A_B"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(underscoreGame.getId()))
			.andExpect(
				jsonPath("$.data.content[0].name").value(SEARCH_PREFIX + "Underscore-A_B"));
	}

	@Test
	void 실제_프로젝션_결과는_이름과_ID_오름차순_및_페이지_메타데이터를_반환한다() throws Exception {
		Game firstAlpha = saveGame(10011L, SORT_PREFIX + "Alpha", "알파", "알파 상세 설명");
		Game secondAlpha = saveGame(10012L, SORT_PREFIX + "Alpha", "알파", "알파 상세 설명");
		Game beta = saveGame(10013L, SORT_PREFIX + "Beta", "베타", "베타 상세 설명");

		mockMvc.perform(
			get("/api/games")
				.param("keyword", SORT_PREFIX)
				.param("page", "0")
				.param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(2))
			.andExpect(jsonPath("$.data.content[0].name").value(SORT_PREFIX + "Alpha"))
			.andExpect(jsonPath("$.data.content[0].id").value(firstAlpha.getId()))
			.andExpect(jsonPath("$.data.content[1].name").value(SORT_PREFIX + "Alpha"))
			.andExpect(jsonPath("$.data.content[1].id").value(secondAlpha.getId()))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(2))
			.andExpect(jsonPath("$.data.totalElements").value(3))
			.andExpect(jsonPath("$.data.totalPages").value(2))
			.andExpect(jsonPath("$.data.hasNext").value(true));

		mockMvc.perform(
			get("/api/games")
				.param("keyword", SORT_PREFIX)
				.param("page", "1")
				.param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(beta.getId()))
			.andExpect(jsonPath("$.data.page").value(1))
			.andExpect(jsonPath("$.data.size").value(2))
			.andExpect(jsonPath("$.data.totalElements").value(3))
			.andExpect(jsonPath("$.data.totalPages").value(2))
			.andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void 게임_목록_항목은_계약_필드만_반환하고_엔티티_상세와_감사_필드는_노출하지_않는다() throws Exception {
		Game game = saveGame(10021L, CARD_PREFIX + "카탄", "게임 설명", "게임 상세 설명");

		assertNotNull(game.getCreatedAt());
		assertNotNull(game.getUpdatedAt());

		mockMvc.perform(get("/api/games").param("keyword", CARD_PREFIX))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].id").value(game.getId()))
			.andExpect(jsonPath("$.data.content[0].bggId").value(10021))
			.andExpect(jsonPath("$.data.content[0].name").value(CARD_PREFIX + "카탄"))
			.andExpect(jsonPath("$.data.content[0].englishName").value("Catan"))
			.andExpect(
				jsonPath("$.data.content[0].imageUrl")
					.value("https://example.com/catan.jpg"))
			.andExpect(jsonPath("$.data.content[0].supportedPlayerCount").value("3~4명"))
			.andExpect(jsonPath("$.data.content[0].tag").value("전략"))
			.andExpect(jsonPath("$.data.content[0].estimatedPlayTime").value("60~90분"))
			.andExpect(jsonPath("$.data.content[0].complexity").value(2.5))
			.andExpect(jsonPath("$.data.content[0].upcomingRoomCount").value(0))
			.andExpect(jsonPath("$.data.content[0].alias").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].description").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].detailDescription").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].createdAt").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].updatedAt").doesNotExist());
	}

	@Test
	void 익명_요청은_세션과_CSRF_토큰_없이_실제_상세_필드와_예정_모임_기본값을_조회한다() throws Exception {
		Game game = saveGame(10031L, "카탄", "게임 설명", "게임 상세 설명");

		mockMvc.perform(get("/api/games/{gameId}", game.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.id").value(game.getId()))
			.andExpect(jsonPath("$.data.bggId").value(10031))
			.andExpect(jsonPath("$.data.name").value("카탄"))
			.andExpect(jsonPath("$.data.englishName").value("Catan"))
			.andExpect(jsonPath("$.data.imageUrl").value("https://example.com/catan.jpg"))
			.andExpect(jsonPath("$.data.supportedPlayerCount").value("3~4명"))
			.andExpect(jsonPath("$.data.tag").value("전략"))
			.andExpect(jsonPath("$.data.estimatedPlayTime").value("60~90분"))
			.andExpect(jsonPath("$.data.complexity").value(2.5))
			.andExpect(jsonPath("$.data.upcomingRoomCount").value(0))
			.andExpect(jsonPath("$.data.alias").value("카탄 기본판"))
			.andExpect(jsonPath("$.data.description").value("게임 설명"))
			.andExpect(jsonPath("$.data.detailDescription").value("게임 상세 설명"))
			.andExpect(jsonPath("$.data.createdAt").doesNotExist())
			.andExpect(jsonPath("$.data.updatedAt").doesNotExist());
	}

	@Test
	void 검색_수치가_NULL이어도_기존_표시_필드는_유지하고_API에_노출하지_않는다() throws Exception {
		Game game = saveGame(10032L, "표시 문자열 유지", "게임 설명", "게임 상세 설명");
		ReflectionTestUtils.setField(game, "minPlayers", null);
		ReflectionTestUtils.setField(game, "maxPlayers", null);
		ReflectionTestUtils.setField(game, "minPlayTimeMinutes", null);
		ReflectionTestUtils.setField(game, "maxPlayTimeMinutes", null);
		gameRepository.saveAndFlush(game);

		mockMvc.perform(get("/api/games/{gameId}", game.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.supportedPlayerCount").value("3~4명"))
			.andExpect(jsonPath("$.data.estimatedPlayTime").value("60~90분"))
			.andExpect(jsonPath("$.data.minPlayers").doesNotExist())
			.andExpect(jsonPath("$.data.maxPlayers").doesNotExist())
			.andExpect(jsonPath("$.data.minPlayTimeMinutes").doesNotExist())
			.andExpect(jsonPath("$.data.maxPlayTimeMinutes").doesNotExist());
	}

	@Test
	void 존재하지_않는_양의_게임_ID는_실제_예외_처리_경로에서_GAME_NOT_FOUND를_반환한다() throws Exception {
		mockMvc.perform(get("/api/games/999999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"))
			.andExpect(jsonPath("$.data").value((Object)null));
	}

	private Game saveGame(long bggId, String name, String description, String detailDescription) {
		Game game = new Game(
			bggId,
			name,
			"Catan",
			"3~4명",
			"전략",
			"60~90분",
			description,
			detailDescription);
		ReflectionTestUtils.setField(game, "alias", "카탄 기본판");
		ReflectionTestUtils.setField(game, "imageUrl", "https://example.com/catan.jpg");
		ReflectionTestUtils.setField(game, "complexity", new java.math.BigDecimal("2.50"));
		return gameRepository.saveAndFlush(game);
	}
}
