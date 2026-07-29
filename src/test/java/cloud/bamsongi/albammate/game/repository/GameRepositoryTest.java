package cloud.bamsongi.albammate.game.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
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
	void 이름을_대소문자_구분없이_부분검색하고_페이지로_조회한다() {
		gameRepository.saveAllAndFlush(
			List.of(
				GameFixture.valid(1001L, "Catan"),
				GameFixture.valid(1002L, "Catan Junior"),
				GameFixture.valid(1003L, "Azul")));

		Page<GameListRow> result = gameRepository.findListRowsByNameContainingIgnoreCase(
			"CATAN", PageRequest.of(0, 1, Sort.by(Sort.Direction.ASC, "bggId")));

		assertEquals(2, result.getTotalElements());
		assertEquals(2, result.getTotalPages());
		assertEquals(1, result.getNumberOfElements());
		assertEquals(
			List.of("Catan"), result.getContent().stream().map(GameListRow::name).toList());
	}

	@Test
	void 요약은_필요한_필드만_조회하고_없는_ID는_empty다() {
		Game savedGame = gameRepository.saveAndFlush(GameFixture.valid());

		Optional<GameSummary> result = gameRepository.findSummaryById(savedGame.getId());

		assertEquals(
			Optional.of(
				new GameSummary(
					savedGame.getId(), savedGame.getBggId(), savedGame.getName())),
			result);
		assertTrue(gameRepository.findSummaryById(999_999L).isEmpty());
	}

	@Test
	void 여러_게임_요약은_존재하는_게임만_반환한다() {
		List<Game> savedGames = gameRepository.saveAllAndFlush(
			List.of(GameFixture.valid(1001L, "카탄"), GameFixture.valid(1002L, "아줄")));

		List<GameSummary> summaries = gameRepository.findSummariesByIds(
			List.of(
				savedGames.getFirst().getId(),
				savedGames.getLast().getId(),
				999_999L));

		assertEquals(
			java.util.Set.of(
				new GameSummary(
					savedGames.getFirst().getId(),
					savedGames.getFirst().getBggId(),
					savedGames.getFirst().getName()),
				new GameSummary(
					savedGames.getLast().getId(),
					savedGames.getLast().getBggId(),
					savedGames.getLast().getName())),
			java.util.Set.copyOf(summaries));
	}
}
