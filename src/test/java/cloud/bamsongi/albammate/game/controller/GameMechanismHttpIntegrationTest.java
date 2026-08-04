package cloud.bamsongi.albammate.game.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameMechanism;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameMechanismRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GameMechanismHttpIntegrationTest {

	private static final String PREFIX = "GameMechanismHttp-";
	private static final Instant REVIEWED_AT = Instant.parse("2026-08-04T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private GameRepository gameRepository;

	@Autowired
	private GameMechanismRepository gameMechanismRepository;

	@Autowired
	private GameMechanismRelationRepository gameMechanismRelationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 검수후_공개된_선택지만_대표_순서와_결정적_이름순으로_반환한다() throws Exception {
		saveMechanism(2040L, "HAND_MANAGEMENT", "핸드 관리", "Hand Management", 1, true);
		saveMechanism(2072L, "DICE_ROLLING", "주사위 굴림", "Dice Rolling", 2, true);
		saveMechanism(9991L, "ALPHA", "가나다", "Alpha", null, true);
		saveMechanism(9992L, "BETA", "나다라", "Beta", null, true);
		saveMechanism(9993L, "PRIVATE", "비공개", "Private", null, false);

		mockMvc.perform(get("/api/game-mechanisms"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.length()").value(4))
			.andExpect(jsonPath("$.data[0].code").value("HAND_MANAGEMENT"))
			.andExpect(jsonPath("$.data[1].code").value("DICE_ROLLING"))
			.andExpect(jsonPath("$.data[2].code").value("ALPHA"))
			.andExpect(jsonPath("$.data[3].code").value("BETA"))
			.andExpect(jsonPath("$.data[0].reviewedAt").doesNotExist());
	}

	@Test
	void 단일_메커니즘은_관계_게임만_반환하고_비공개_코드는_검증오류다() throws Exception {
		GameMechanism hand = saveMechanism(2040L, "HAND_MANAGEMENT", "핸드 관리", "Hand Management", 1, true);
		GameMechanism privateMechanism = saveMechanism(9993L, "PRIVATE", "비공개", "Private", null, false);
		Game matched = saveGame(100001L, "Single matched");
		saveGame(100002L, "Single unmatched");
		link(matched, hand);

		mockMvc.perform(get("/api/games").param("keyword", PREFIX + "Single").param("mechanism", "HAND_MANAGEMENT"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(matched.getId()));

		mockMvc.perform(get("/api/games").param("mechanism", privateMechanism.getCode()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(get("/api/games").param("mechanism", "UNKNOWN_MECHANISM"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
	}

	@Test
	void 여러_메커니즘은_OR로_중복없이_반환한다() throws Exception {
		GameMechanism hand = saveMechanism(2040L, "HAND_MANAGEMENT", "핸드 관리", "Hand Management", 1, true);
		GameMechanism dice = saveMechanism(2072L, "DICE_ROLLING", "주사위 굴림", "Dice Rolling", 2, true);
		Game both = saveGame(100011L, "Multiple both");
		Game diceOnly = saveGame(100012L, "Multiple dice");
		link(both, hand);
		link(both, dice);
		link(diceOnly, dice);

		mockMvc.perform(
			get("/api/games")
				.param("keyword", PREFIX + "Multiple")
				.param("mechanism", "HAND_MANAGEMENT")
				.param("mechanism", "DICE_ROLLING")
				.param("mechanism", "DICE_ROLLING"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.content.length()").value(2));
	}

	@Test
	void 메커니즘과_기존_조건은_AND로_결합하고_정렬과_페이지를_유지한다() throws Exception {
		GameMechanism hand = saveMechanism(2040L, "HAND_MANAGEMENT", "핸드 관리", "Hand Management", 1, true);
		Game alpha = saveGame(100021L, "Combined Alpha");
		Game beta = saveGame(100022L, "Combined Beta");
		Game wrongPlayers = saveGame(100023L, "Combined Wrong");
		ReflectionTestUtils.setField(wrongPlayers, "minPlayers", 5);
		ReflectionTestUtils.setField(wrongPlayers, "maxPlayers", 5);
		gameRepository.saveAndFlush(wrongPlayers);
		link(alpha, hand);
		link(beta, hand);
		link(wrongPlayers, hand);

		mockMvc.perform(
			get("/api/games")
				.param("keyword", PREFIX + "Combined")
				.param("mechanism", "HAND_MANAGEMENT")
				.param("playerCount", "2")
				.param("page", "1")
				.param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.totalPages").value(2))
			.andExpect(jsonPath("$.data.content[0].id").value(beta.getId()));
	}

	@Test
	void 메커니즘을_생략하면_관계없는_게임도_유지하고_중복_관계는_저장할수없다() throws Exception {
		GameMechanism hand = saveMechanism(2040L, "HAND_MANAGEMENT", "핸드 관리", "Hand Management", 1, true);
		Game linked = saveGame(100031L, "Omitted linked");
		saveGame(100032L, "Omitted no relation");
		link(linked, hand);

		mockMvc.perform(get("/api/games").param("keyword", PREFIX + "Omitted"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2));

		assertThrows(
			DataIntegrityViolationException.class,
			() -> jdbcTemplate.update(
				"insert into game_mechanism_relations (game_id, mechanism_id) values (?, ?)",
				linked.getId(),
				hand.getId()));
	}

	private GameMechanism saveMechanism(
		long bggId,
		String code,
		String nameKo,
		String nameEn,
		Integer featuredOrder,
		boolean isPublic) {
		return gameMechanismRepository.saveAndFlush(
			new GameMechanism(
				bggId,
				code,
				nameKo,
				nameEn,
				featuredOrder,
				isPublic,
				"Issue #351",
				isPublic ? "beyejin" : null,
				isPublic ? REVIEWED_AT : null));
	}

	private Game saveGame(long bggId, String name) {
		Game game = GameFixture.valid(bggId, PREFIX + name);
		ReflectionTestUtils.setField(game, "minPlayers", 2);
		ReflectionTestUtils.setField(game, "maxPlayers", 4);
		return gameRepository.saveAndFlush(game);
	}

	private void link(Game game, GameMechanism mechanism) {
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(game, mechanism));
	}
}
