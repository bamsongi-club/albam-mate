package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor.SpecificationFluentQuery;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameCategory;
import cloud.bamsongi.albammate.game.entity.GameCategoryRelation;
import cloud.bamsongi.albammate.game.entity.GameMechanism;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.repository.GameCategoryRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameMechanismRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;
import cloud.bamsongi.albammate.game.service.GameQueryService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest
@Transactional
class GameListFilterPostgresTest extends SharedPostgresIntegrationSupport {

	@MockitoSpyBean
	private GameRepository gameRepository;

	@Autowired
	private GameQueryService gameQueryService;

	@Autowired
	private GameMechanismRepository gameMechanismRepository;

	@Autowired
	private GameMechanismRelationRepository gameMechanismRelationRepository;

	@Autowired
	private GameCategoryRepository gameCategoryRepository;

	@Autowired
	private GameCategoryRelationRepository gameCategoryRelationRepository;

	@Test
	void PostgreSQL_게임목록은_count없이_Slice_경계와_고정정렬을_보존한다() {
		Game first = saveGame(9901L, "Slice-Alpha", 2, 4, 20, new BigDecimal("2.00"));
		Game second = saveGame(9902L, "Slice-Beta", 2, 4, 20, new BigDecimal("2.00"));
		Game third = saveGame(9903L, "Slice-Gamma", 2, 4, 20, new BigDecimal("2.00"));
		GameListRequest firstRequest = new GameListRequest();
		firstRequest.setKeyword("Slice-");
		firstRequest.setSize(2);
		GameListRequest secondRequest = new GameListRequest();
		secondRequest.setKeyword("Slice-");
		secondRequest.setPage(1);
		secondRequest.setSize(2);

		var firstPage = gameQueryService.findPage(firstRequest, null);
		var secondPage = gameQueryService.findPage(secondRequest, null);

		assertEquals(List.of(first.getId(), second.getId()),
			firstPage.getContent().stream().map(game -> game.id()).toList());
		assertEquals(true, firstPage.hasNext());
		assertEquals(List.of(third.getId()), secondPage.getContent().stream().map(game -> game.id()).toList());
		assertEquals(false, secondPage.hasNext());
		ArgumentCaptor<Function> queryCallback = ArgumentCaptor.forClass(Function.class);
		verify(gameRepository, times(2)).findBy(any(Specification.class), queryCallback.capture());
		SpecificationFluentQuery<Game> query = mock(SpecificationFluentQuery.class);
		when(query.slice(any(Pageable.class))).thenReturn(new SliceImpl<>(List.of()));
		queryCallback.getAllValues().forEach(callback -> callback.apply(query));
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(query, times(2)).slice(pageable.capture());
		assertEquals(
			List.of(
				PageRequest.of(0, 2, Sort.by(Sort.Order.desc("popularityScore"), Sort.Order.asc("name"),
					Sort.Order.asc("id"))),
				PageRequest.of(1, 2, Sort.by(Sort.Order.desc("popularityScore"), Sort.Order.asc("name"),
					Sort.Order.asc("id")))),
			pageable.getAllValues());
	}

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
		saveGame(1005L, "BelowComplexity", 2, 10, 20, new BigDecimal("1.00"));
		saveGame(1006L, "AboveComplexity", 2, 10, 20, new BigDecimal("3.00"));

		GameListSearchCriteria criteria = criteria(request -> {
			request.setPlayerCount(10);
			request.setPlayTime(List.of(GamePlayTimeFilter.OVER_10_TO_20));
			request.setComplexityMin(new BigDecimal("2.00"));
			request.setComplexityMax(new BigDecimal("2.00"));
		});

		var firstPage = gameRepository.findAll(
			GameListSpecification.from(criteria),
			PageRequest.of(0, 1, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
		var secondPage = gameRepository.findAll(
			GameListSpecification.from(criteria),
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
		Game oneToFour = saveGame(1005L, "OneToFour", 1, 4, 20, new BigDecimal("2.00"));

		assertEquals(
			List.of(oneToFour.getId(), twoToFour.getId()),
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

	@Test
	void PostgreSQL에서_최연소_참여자_나이와_카테고리를_AND로_결합한_뒤_정렬_페이지_전체건수를_계산한다() {
		GameCategory strategy = gameCategoryRepository.saveAndFlush(
			new GameCategory("STRATEGY", "전략", "Strategy", "strategygames", 1));
		Game alpha = saveGameWithAge(1201L, "Alpha", 10);
		Game beta = saveGameWithAge(1202L, "Beta", 8);
		Game tooOld = saveGameWithAge(1203L, "TooOld", 11);
		Game missingAge = saveGameWithAge(1204L, "MissingAge", null);
		Game wrongCategory = saveGameWithAge(1205L, "WrongCategory", 10);
		gameCategoryRelationRepository.saveAndFlush(new GameCategoryRelation(alpha, strategy));
		gameCategoryRelationRepository.saveAndFlush(new GameCategoryRelation(beta, strategy));
		gameCategoryRelationRepository.saveAndFlush(new GameCategoryRelation(tooOld, strategy));
		gameCategoryRelationRepository.saveAndFlush(new GameCategoryRelation(missingAge, strategy));

		GameListRequest request = new GameListRequest();
		request.setYoungestPlayerAge(10);
		request.setCategory(List.of("STRATEGY"));
		var firstPage = gameRepository.findAll(
			GameListSpecification.from(GameListSearchCriteria.from(request)),
			PageRequest.of(0, 1, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));
		var secondPage = gameRepository.findAll(
			GameListSpecification.from(GameListSearchCriteria.from(request)),
			PageRequest.of(1, 1, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))));

		assertEquals(2, firstPage.getTotalElements());
		assertEquals(List.of(alpha.getId()), firstPage.getContent().stream().map(Game::getId).toList());
		assertEquals(List.of(beta.getId()), secondPage.getContent().stream().map(Game::getId).toList());
	}

	private Game saveGameWithAge(long bggId, String name, Integer minAge) {
		Game game = saveGame(bggId, name, 2, 4, 20, new BigDecimal("2.00"));
		ReflectionTestUtils.setField(game, "minAge", minAge);
		return gameRepository.saveAndFlush(game);
	}

	private List<Long> ids(Consumer<GameListRequest> customizer) {
		return gameRepository
			.findAll(
				GameListSpecification.from(criteria(customizer)),
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
