package cloud.bamsongi.albammate.game.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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
	void 기존_인원_조건은_범위_포함과_10명_이상을_검증값으로_조회한다() {
		Game oneToFour = saveGame(1001L, "OneToFour", 1, 4, 20, new BigDecimal("2.00"));
		Game twoToTen = saveGame(1002L, "TwoToTen", 2, 10, 20, new BigDecimal("2.00"));
		saveGame(1003L, "MissingPlayers", null, null, 20, new BigDecimal("2.00"));

		assertEquals(List.of(oneToFour.getId()), ids(criteria(request -> request.setPlayerCount(1))));
		assertEquals(List.of(twoToTen.getId()), ids(criteria(request -> request.setPlayerCount(10))));
	}

	@Test
	void 인원_범위_조건은_요청_범위_전체를_지원하는_게임만_반환한다() {
		Game oneToTwo = saveGame(1001L, "OneToTwo", 1, 2, 20, new BigDecimal("2.00"));
		Game twoToFour = saveGame(1002L, "TwoToFour", 2, 4, 20, new BigDecimal("2.00"));
		Game fourToSix = saveGame(1003L, "FourToSix", 4, 6, 20, new BigDecimal("2.00"));
		saveGame(1004L, "MissingPlayers", null, null, 20, new BigDecimal("2.00"));

		assertEquals(
			List.of(twoToFour.getId()),
			ids(criteria(request -> {
				request.setPlayerCountMin(2);
				request.setPlayerCountMax(4);
			})));
		assertEquals(List.of(fourToSix.getId()), ids(criteria(request -> request.setPlayerCountMin(5))));
		assertEquals(
			List.of(oneToTwo.getId(), twoToFour.getId()),
			ids(criteria(request -> request.setPlayerCountMax(2))));
	}

	@Test
	void 경계_정확_일치는_전달한_인원_경계와_같은_게임만_반환한다() {
		Game oneToTwo = saveGame(1001L, "OneToTwo", 1, 2, 20, new BigDecimal("2.00"));
		Game twoToFour = saveGame(1002L, "TwoToFour", 2, 4, 20, new BigDecimal("2.00"));
		Game fourToSix = saveGame(1003L, "FourToSix", 4, 6, 20, new BigDecimal("2.00"));
		saveGame(1004L, "MissingPlayers", null, null, 20, new BigDecimal("2.00"));

		assertEquals(
			List.of(twoToFour.getId()),
			ids(criteria(request -> {
				request.setPlayerCountExact(true);
				request.setPlayerCountMin(2);
				request.setPlayerCountMax(4);
			})));
		assertEquals(
			List.of(fourToSix.getId()),
			ids(criteria(request -> {
				request.setPlayerCountExact(true);
				request.setPlayerCountMin(4);
			})));
		assertEquals(
			List.of(oneToTwo.getId()),
			ids(criteria(request -> {
				request.setPlayerCountExact(true);
				request.setPlayerCountMax(2);
			})));
	}

	@Test
	void 맞출_경계가_없는_정확_일치는_인원_조건을_적용하지_않는다() {
		Game onlyGame = saveGame(1001L, "OnlyGame", 2, 4, 20, new BigDecimal("2.00"));
		Game missingPlayers = saveGame(1002L, "ZMissingPlayers", null, null, 20, new BigDecimal("2.00"));

		assertEquals(
			List.of(onlyGame.getId(), missingPlayers.getId()),
			ids(criteria(request -> request.setPlayerCountExact(true))));
	}

	@Test
	void 전용_인원은_1인과_2인을_OR로_결합하고_중복_없이_반환한다() {
		Game solo = saveGame(1001L, "Solo", 1, 1, 20, new BigDecimal("2.00"));
		Game duo = saveGame(1002L, "Duo", 2, 2, 20, new BigDecimal("2.00"));
		saveGame(1003L, "OneToTwo", 1, 2, 20, new BigDecimal("2.00"));
		saveGame(1004L, "MissingPlayers", null, null, 20, new BigDecimal("2.00"));

		assertEquals(
			List.of(duo.getId(), solo.getId()),
			ids(criteria(request -> request.setExclusivePlayerCount(List.of(1, 2)))));
		assertEquals(
			List.of(duo.getId(), solo.getId()),
			ids(criteria(request -> request.setExclusivePlayerCount(List.of(1, 1, 2, 2)))));
		assertEquals(
			List.of(solo.getId()),
			ids(criteria(request -> request.setExclusivePlayerCount(List.of(1)))));
	}

	@Test
	void 플레이_시간_6구간은_경계값을_정확히_한_구간에만_넣는다() {
		Game upToTen = saveGame(1001L, "UpToTen", 2, 4, 10, new BigDecimal("2.00"));
		Game twenty = saveGame(1002L, "Twenty", 2, 4, 20, new BigDecimal("2.00"));
		Game thirty = saveGame(1003L, "Thirty", 2, 4, 30, new BigDecimal("2.00"));
		Game sixty = saveGame(1004L, "Sixty", 2, 4, 60, new BigDecimal("2.00"));
		Game underNinety = saveGame(1005L, "UnderNinety", 2, 4, 89, new BigDecimal("2.00"));
		Game ninety = saveGame(1006L, "Ninety", 2, 4, 90, new BigDecimal("2.00"));
		saveGame(1007L, "MissingPlayTime", 2, 4, null, new BigDecimal("2.00"));

		assertEquals(List.of(upToTen.getId()), ids(playTime(GamePlayTimeFilter.UP_TO_10)));
		assertEquals(List.of(twenty.getId()), ids(playTime(GamePlayTimeFilter.OVER_10_TO_20)));
		assertEquals(List.of(thirty.getId()), ids(playTime(GamePlayTimeFilter.OVER_20_TO_30)));
		assertEquals(List.of(sixty.getId()), ids(playTime(GamePlayTimeFilter.OVER_30_TO_60)));
		assertEquals(List.of(underNinety.getId()), ids(playTime(GamePlayTimeFilter.OVER_60_UNDER_90)));
		assertEquals(List.of(ninety.getId()), ids(playTime(GamePlayTimeFilter.AT_LEAST_90)));
	}

	@Test
	void 여러_플레이_시간_구간은_OR로_결합하고_반복_전달을_한번처럼_처리한다() {
		Game upToTen = saveGame(1001L, "AUpToTen", 2, 4, 10, new BigDecimal("2.00"));
		saveGame(1002L, "BThirty", 2, 4, 30, new BigDecimal("2.00"));
		Game ninety = saveGame(1003L, "CNinety", 2, 4, 90, new BigDecimal("2.00"));

		assertEquals(
			List.of(upToTen.getId(), ninety.getId()),
			ids(playTime(GamePlayTimeFilter.UP_TO_10, GamePlayTimeFilter.AT_LEAST_90)));
		assertEquals(
			List.of(upToTen.getId()),
			ids(playTime(GamePlayTimeFilter.UP_TO_10, GamePlayTimeFilter.UP_TO_10)));
	}

	@Test
	void 복잡도_조건은_닫힌_구간과_NULL_제외를_적용한다() {
		saveGame(1001L, "Easy", 2, 4, 20, new BigDecimal("1.00"));
		Game middle = saveGame(1002L, "Middle", 2, 4, 20, new BigDecimal("3.00"));
		saveGame(1003L, "Hard", 2, 4, 20, new BigDecimal("5.00"));
		saveGame(1004L, "MissingComplexity", 2, 4, 20, null);

		assertEquals(List.of(middle.getId()), ids(criteria(request -> {
			request.setComplexityMin(new BigDecimal("2.00"));
			request.setComplexityMax(new BigDecimal("4.00"));
		})));
	}

	@Test
	void 키워드_예정모임과_새_인원_시간_조건을_AND로_결합하고_중복_ID를_한번처럼_처리한다() {
		Game match = saveGame(1001L, "Catan Match", 2, 4, 30, new BigDecimal("2.50"));
		Game wrongTime = saveGame(1002L, "Catan Long", 2, 4, 90, new BigDecimal("2.50"));
		saveGame(1003L, "Azul Match", 2, 4, 30, new BigDecimal("2.50"));

		GameListSearchCriteria criteria = criteria(request -> {
			request.setKeyword("catan");
			request.setUpcomingOnly(true);
			request.setPlayerCountMin(2);
			request.setPlayerCountMax(4);
			request.setPlayTime(List.of(GamePlayTimeFilter.OVER_20_TO_30));
			request.setComplexityMin(new BigDecimal("2.00"));
			request.setComplexityMax(new BigDecimal("3.00"));
		}).withUpcomingGameIds(List.of(match.getId(), match.getId(), wrongTime.getId()));

		assertEquals(List.of(match.getId()), ids(criteria));
	}

	@Test
	void 모든_조건을_적용한_결과로_정렬과_전체건수를_계산한다() {
		Game firstAlpha = saveGame(1001L, "Alpha", 2, 4, 30, new BigDecimal("2.00"));
		Game secondAlpha = saveGame(1002L, "Alpha", 2, 4, 30, new BigDecimal("2.00"));
		saveGame(1003L, "Beta", 2, 4, 30, new BigDecimal("2.00"));
		saveGame(1004L, "Excluded", 2, 4, 90, new BigDecimal("2.00"));

		Page<Game> result = find(pagedCriteria(), 0, 1);

		assertEquals(3, result.getTotalElements());
		assertEquals(3, result.getTotalPages());
		assertEquals(List.of(firstAlpha.getId()), result.getContent().stream().map(Game::getId).toList());
		assertEquals(
			List.of(secondAlpha.getId()),
			find(pagedCriteria(), 1, 1).getContent().stream().map(Game::getId).toList());
	}

	@Test
	void 요약은_필요한_필드만_조회하고_없는_ID는_empty다() {
		Game savedGame = gameRepository.saveAndFlush(GameFixture.valid());

		Optional<GameSummary> result = gameRepository.findSummaryById(savedGame.getId());

		assertEquals(Optional.of(new GameSummary(savedGame.getId(), savedGame.getBggId(), savedGame.getName())),
			result);
		assertTrue(gameRepository.findSummaryById(999_999L).isEmpty());
	}

	private GameListSearchCriteria pagedCriteria() {
		return criteria(request -> {
			request.setPlayerCountMin(2);
			request.setPlayerCountMax(4);
			request.setPlayTime(List.of(GamePlayTimeFilter.OVER_20_TO_30));
			request.setComplexityMin(new BigDecimal("2.00"));
			request.setComplexityMax(new BigDecimal("2.00"));
		});
	}

	private List<Long> ids(GameListSearchCriteria criteria) {
		return find(criteria, 0, 10).getContent().stream().map(Game::getId).toList();
	}

	private Page<Game> find(GameListSearchCriteria criteria, int page, int size) {
		return gameRepository.findAll(
			criteria.toSpecification(),
			PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
	}

	private GameListSearchCriteria playTime(GamePlayTimeFilter... playTimes) {
		return criteria(request -> request.setPlayTime(List.of(playTimes)));
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
		Game game = GameFixture.valid(bggId, name);
		ReflectionTestUtils.setField(game, "minPlayers", minPlayers);
		ReflectionTestUtils.setField(game, "maxPlayers", maxPlayers);
		ReflectionTestUtils.setField(game, "minPlayTimeMinutes", maxPlayTimeMinutes == null ? null : 1);
		ReflectionTestUtils.setField(game, "maxPlayTimeMinutes", maxPlayTimeMinutes);
		ReflectionTestUtils.setField(game, "complexity", complexity);
		return gameRepository.saveAndFlush(game);
	}
}
