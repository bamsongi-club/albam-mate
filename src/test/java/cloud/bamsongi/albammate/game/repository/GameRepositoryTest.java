package cloud.bamsongi.albammate.game.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;
import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;

@DataJpaTest
@Import({JpaConfig.class, TimeConfig.class})
class GameRepositoryTest {

	@Autowired
	private GameRepository gameRepository;

	@Test
	void 저장하면_감사_시각이_채워진다() {
		Game savedGame = gameRepository.saveAndFlush(GameFixture.valid());

		assertNotNull(savedGame.getCreatedAt());
		assertNotNull(savedGame.getUpdatedAt());
	}

	@Test
	void 인원_조건은_범위_포함과_10명_이상을_검증값으로_조회한다() {
		Game oneToFour = saveGame(1001L, "OneToFour", 1, 4, 20, new BigDecimal("2.00"));
		Game twoToTen = saveGame(1002L, "TwoToTen", 2, 10, 20, new BigDecimal("2.00"));
		saveGame(1003L, "MissingPlayers", null, null, 20, new BigDecimal("2.00"));

		assertEquals(
			List.of(oneToFour.getId()),
			find(criteria(1, null, null, null, null, null), 10).getContent().stream().map(Game::getId).toList());
		assertEquals(
			List.of(twoToTen.getId()),
			find(criteria(10, null, null, null, null, null), 10).getContent().stream().map(Game::getId).toList());
	}

	@Test
	void 플레이_시간과_복잡도_조건은_경계와_NULL_제외를_적용한다() {
		Game shortGame = saveGame(1001L, "Short", 2, 4, 20, new BigDecimal("1.00"));
		Game mediumGame = saveGame(1002L, "Medium", 2, 4, 60, new BigDecimal("3.00"));
		Game longGame = saveGame(1003L, "Long", 2, 4, 61, new BigDecimal("5.00"));
		saveGame(1004L, "MissingValues", 2, 4, null, null);

		assertEquals(List.of(shortGame.getId()), ids(criteria(null, GamePlayTimeFilter.SHORT, null, null, null, null)));
		assertEquals(List.of(mediumGame.getId()),
			ids(criteria(null, GamePlayTimeFilter.MEDIUM, null, null, null, null)));
		assertEquals(List.of(longGame.getId()), ids(criteria(null, GamePlayTimeFilter.LONG, null, null, null, null)));
		assertEquals(List.of(mediumGame.getId()), ids(criteria(null, null, "2.00", "4.00", null, null)));
	}

	@Test
	void 키워드_예정모임과_수치_조건을_AND로_결합하고_중복_ID를_한번처럼_처리한다() {
		Game match = saveGame(1001L, "Catan Match", 3, 4, 20, new BigDecimal("2.50"));
		Game wrongTime = saveGame(1002L, "Catan Long", 3, 4, 61, new BigDecimal("2.50"));
		saveGame(1003L, "Azul Match", 3, 4, 20, new BigDecimal("2.50"));

		GameListSearchCriteria criteria = criteria(4, GamePlayTimeFilter.SHORT, "2.00", "3.00", "catan", true)
			.withUpcomingGameIds(List.of(match.getId(), match.getId(), wrongTime.getId()));

		assertEquals(List.of(match.getId()), ids(criteria));
	}

	@Test
	void 모든_조건을_적용한_결과로_정렬과_전체건수를_계산한다() {
		Game firstAlpha = saveGame(1001L, "Alpha", 2, 4, 20, new BigDecimal("2.00"));
		Game secondAlpha = saveGame(1002L, "Alpha", 2, 4, 20, new BigDecimal("2.00"));
		saveGame(1003L, "Beta", 2, 4, 20, new BigDecimal("2.00"));
		saveGame(1004L, "Excluded", 2, 4, 61, new BigDecimal("2.00"));

		Page<Game> result = find(criteria(2, GamePlayTimeFilter.SHORT, "2.00", "2.00", null, null), 1);

		assertEquals(3, result.getTotalElements());
		assertEquals(3, result.getTotalPages());
		assertEquals(List.of(firstAlpha.getId()), result.getContent().stream().map(Game::getId).toList());
		assertEquals(
			List.of(secondAlpha.getId()),
			find(criteria(2, GamePlayTimeFilter.SHORT, "2.00", "2.00", null, null), 1, 1)
				.getContent().stream().map(Game::getId).toList());
	}

	@Test
	void 요약은_필요한_필드만_조회하고_없는_ID는_empty다() {
		Game savedGame = gameRepository.saveAndFlush(GameFixture.valid());

		Optional<GameSummary> result = gameRepository.findSummaryById(savedGame.getId());

		assertEquals(Optional.of(new GameSummary(savedGame.getId(), savedGame.getBggId(), savedGame.getName())),
			result);
		assertTrue(gameRepository.findSummaryById(999_999L).isEmpty());
	}

	private List<Long> ids(GameListSearchCriteria criteria) {
		return find(criteria, 10).getContent().stream().map(Game::getId).toList();
	}

	private Page<Game> find(GameListSearchCriteria criteria, int size) {
		return find(criteria, 0, size);
	}

	private Page<Game> find(GameListSearchCriteria criteria, int page, int size) {
		return gameRepository.findAll(
			criteria.toSpecification(),
			PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
	}

	private GameListSearchCriteria criteria(
		Integer playerCount,
		GamePlayTimeFilter playTime,
		String complexityMin,
		String complexityMax,
		String keyword,
		Boolean upcomingOnly) {
		GameListRequest request = new GameListRequest();
		request.setKeyword(keyword);
		request.setUpcomingOnly(upcomingOnly);
		request.setPlayerCount(playerCount);
		request.setPlayTime(playTime);
		request.setComplexityMin(complexityMin == null ? null : new BigDecimal(complexityMin));
		request.setComplexityMax(complexityMax == null ? null : new BigDecimal(complexityMax));
		return GameListSearchCriteria.from(request);
	}

	private Game saveGame(
		long bggId,
		String name,
		Integer minPlayers,
		Integer maxPlayers,
		Integer maxPlayTimeMinutes,
		BigDecimal complexity) {
		Game game = GameFixture.valid(bggId, name);
		ReflectionTestUtils.setField(game, "minPlayers", minPlayers);
		ReflectionTestUtils.setField(game, "maxPlayers", maxPlayers);
		ReflectionTestUtils.setField(game, "minPlayTimeMinutes", maxPlayTimeMinutes == null ? null : 1);
		ReflectionTestUtils.setField(game, "maxPlayTimeMinutes", maxPlayTimeMinutes);
		ReflectionTestUtils.setField(game, "complexity", complexity);
		return gameRepository.saveAndFlush(game);
	}
}
