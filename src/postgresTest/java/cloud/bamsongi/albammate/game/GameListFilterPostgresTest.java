package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

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

	@Test
	void PostgreSQL에서_모든_게임_조건과_정렬_페이지_전체건수를_함께_적용한다() {
		saveGame(1001L, "Alpha", 2, 4, 20, new BigDecimal("2.00"));
		Game second = saveGame(1002L, "Alpha", 2, 10, 20, new BigDecimal("2.00"));
		saveGame(1003L, "Beta", 2, 10, 61, new BigDecimal("2.00"));
		saveGame(1004L, "Missing", null, null, null, null);

		GameListSearchCriteria criteria = criteria(10, GamePlayTimeFilter.SHORT, "2.00", "2.00");

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

	private GameListSearchCriteria criteria(
		Integer playerCount, GamePlayTimeFilter playTime, String complexityMin, String complexityMax) {
		GameListRequest request = new GameListRequest();
		request.setPlayerCount(playerCount);
		request.setPlayTime(playTime);
		request.setComplexityMin(new BigDecimal(complexityMin));
		request.setComplexityMax(new BigDecimal(complexityMax));
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
}
