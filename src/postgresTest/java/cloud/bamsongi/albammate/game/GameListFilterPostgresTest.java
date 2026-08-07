package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameMechanism;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.repository.GameMechanismRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;

@Testcontainers
@SpringBootTest
@Transactional
class GameListFilterPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("game_list_filter_test");

	@Autowired
	private GameRepository gameRepository;

	@Autowired
	private GameMechanismRepository gameMechanismRepository;

	@Autowired
	private GameMechanismRelationRepository gameMechanismRelationRepository;

	@Test
	void PostgreSQL에서_메커니즘_EXISTS_조건은_OR와_다른_조건_AND를_중복없이_적용한다() {
		GameMechanism hand = saveMechanism(2040L, "HAND_MANAGEMENT", true);
		GameMechanism dice = saveMechanism(2072L, "DICE_ROLLING", true);
		Game both = saveGame(1101L, "Alpha", 2, 4, 20, new BigDecimal("2.00"));
		Game diceOnly = saveGame(1102L, "Beta", 2, 4, 20, new BigDecimal("2.00"));
		saveGame(1103L, "Gamma", 5, 5, 20, new BigDecimal("2.00"));
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(both, hand));
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(both, dice));
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(diceOnly, dice));

		assertEquals(
			List.of(both.getId(), diceOnly.getId()),
			ids(request -> {
				request.setMechanism(List.of("HAND_MANAGEMENT", "DICE_ROLLING", "DICE_ROLLING"));
				request.setPlayerCount(2);
			}));
	}

	@Test
	void PostgreSQL에서_모든_게임_조건과_정렬_페이지_전체건수를_함께_적용한다() {
		saveGame(1001L, "Alpha", 2, 4, 20, new BigDecimal("2.00"));
		Game second = saveGame(1002L, "Alpha", 2, 10, 20, new BigDecimal("2.00"));
		saveGame(1003L, "Beta", 2, 10, 61, new BigDecimal("2.00"));
		saveGame(1004L, "Missing", null, null, null, null);

		GameListSearchCriteria criteria = criteria(request -> {
			request.setPlayerCount(10);
			request.setPlayTime(List.of(GamePlayTimeFilter.OVER_10_TO_20));
			request.setComplexityMin(new BigDecimal("2.00"));
			request.setComplexityMax(new BigDecimal("2.00"));
		});

		var firstPage = gameRepository.findAll(
			criteria.toSpecification(),
			PageRequest.of(0, 1, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
		var secondPage = gameRepository.findAll(
			criteria.toSpecification(),
			PageRequest.of(1, 1, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));

		assertEquals(1, firstPage.getTotalElements());
		assertEquals(List.of(second.getId()), firstPage.getContent().stream().map(Game::getId).toList());
		assertEquals(List.of(), secondPage.getContent());
	}

	@Test
	void PostgreSQL에서_인원_범위_전용_인원과_플레이_시간_경계를_판정한다() {
		Game solo = saveGame(1001L, "Solo", 1, 1, 10, new BigDecimal("2.00"));
		Game twoToFour = saveGame(1002L, "TwoToFour", 2, 4, 89, new BigDecimal("2.00"));
		Game twoToTwo = saveGame(1003L, "TwoToTwo", 2, 2, 90, new BigDecimal("2.00"));
		saveGame(1004L, "Missing", null, null, null, null);

		assertEquals(
			List.of(twoToFour.getId()),
			ids(request -> {
				request.setPlayerCountMin(2);
				request.setPlayerCountMax(4);
			}));
		assertEquals(
			List.of(twoToFour.getId()),
			ids(request -> {
				request.setPlayerCountExact(true);
				request.setPlayerCountMin(2);
				request.setPlayerCountMax(4);
			}));
		assertEquals(
			List.of(solo.getId(), twoToTwo.getId()),
			ids(request -> request.setExclusivePlayerCount(List.of(1, 2))));
		assertEquals(
			List.of(twoToFour.getId()),
			ids(request -> request.setPlayTime(List.of(GamePlayTimeFilter.OVER_60_UNDER_90))));
		assertEquals(
			List.of(solo.getId(), twoToTwo.getId()),
			ids(request -> request.setPlayTime(
				List.of(GamePlayTimeFilter.UP_TO_10, GamePlayTimeFilter.AT_LEAST_90))));
	}

	private List<Long> ids(Consumer<GameListRequest> customizer) {
		return gameRepository
			.findAll(
				criteria(customizer).toSpecification(),
				PageRequest.of(0, 10, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))))
			.getContent().stream().map(Game::getId).toList();
	}

	private GameListSearchCriteria criteria(Consumer<GameListRequest> customizer) {
		GameListRequest request = new GameListRequest();
		customizer.accept(request);
		return GameListSearchCriteria.from(request);
	}

	private Game saveGame(
		long bggId,
		String name,
		Integer minPlayers,
		Integer maxPlayers,
		Integer maxPlayTimeMinutes,
		BigDecimal complexity) {
		Game game = new Game(
			bggId,
			name,
			"Game",
			"2~4명",
			"전략",
			"20분",
			"설명",
			"상세 설명");
		ReflectionTestUtils.setField(game, "minPlayers", minPlayers);
		ReflectionTestUtils.setField(game, "maxPlayers", maxPlayers);
		ReflectionTestUtils.setField(game, "minPlayTimeMinutes", maxPlayTimeMinutes == null ? null : 1);
		ReflectionTestUtils.setField(game, "maxPlayTimeMinutes", maxPlayTimeMinutes);
		ReflectionTestUtils.setField(game, "complexity", complexity);
		return gameRepository.saveAndFlush(game);
	}

	private GameMechanism saveMechanism(long bggId, String code, boolean isPublic) {
		return gameMechanismRepository.saveAndFlush(
			new GameMechanism(
				bggId,
				code,
				code,
				code,
				isPublic ? code + " 방식을 활용해요." : null,
				null,
				isPublic,
				"Issue #351",
				isPublic ? "beyejin" : null,
				isPublic ? Instant.parse("2026-08-04T00:00:00Z") : null));
	}
}
