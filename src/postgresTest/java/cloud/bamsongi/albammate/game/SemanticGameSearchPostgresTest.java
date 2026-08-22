package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import cloud.bamsongi.albammate.game.contract.SparseCandidateSource;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameCategory;
import cloud.bamsongi.albammate.game.entity.GameCategoryRelation;
import cloud.bamsongi.albammate.game.entity.GameMechanism;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.entity.UserPlayedGame;
import cloud.bamsongi.albammate.game.repository.GameCategoryRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;
import cloud.bamsongi.albammate.game.service.SemanticGameSearchQueryService;
import cloud.bamsongi.albammate.game.service.SemanticGameSearchService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@Testcontainers
@SpringBootTest
@Transactional
class SemanticGameSearchPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private SemanticGameSearchService semanticGameSearchService;
	@Autowired
	private SemanticGameSearchQueryService semanticGameSearchQueryService;
	@Autowired
	private GameRepository gameRepository;
	@Autowired
	private GameCategoryRepository gameCategoryRepository;
	@Autowired
	private GameCategoryRelationRepository gameCategoryRelationRepository;
	@Autowired
	private GameMechanismRepository gameMechanismRepository;
	@Autowired
	private GameMechanismRelationRepository gameMechanismRelationRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UserPlayedGameRepository userPlayedGameRepository;
	@MockitoBean
	private DenseCandidateSource candidateSource;
	@MockitoBean
	private SparseCandidateSource sparseCandidateSource;

	/**
	 * 이 파일의 기존 테스트는 #983 이전 dense-only 계약을 그대로 검증한다. sparse candidate를 기본
	 * 실패로 두어 dense-only 경로(순수 relevance 정렬)를 그대로 재현하고, hybrid 결합 자체는
	 * 별도 postgresTest가 검증한다.
	 */
	@BeforeEach
	void sparseCandidateFailsByDefault() {
		when(sparseCandidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());
	}

	@Test
	void T1_dense_후보에_P1_hard_filter와_현재사용자_playedFilter를_PostgreSQL에서_재검증한다() {
		GameCategory strategy = category("STRATEGY");
		GameMechanism publicMechanism = mechanism("WORKER_PLACEMENT", true);
		User user = user("t1");
		Game eligible = game(836_101L, "Eligible", 2, 4);
		Game unplayed = game(836_102L, "Unplayed", 2, 4);
		Game wrongPlayerCount = game(836_103L, "Wrong-player", 3, 4);
		Game privateMechanism = game(836_104L, "Private-mechanism", 2, 4);
		link(eligible, strategy, publicMechanism);
		link(unplayed, strategy, publicMechanism);
		link(wrongPlayerCount, strategy, publicMechanism);
		link(privateMechanism, strategy, mechanism("PRIVATE", false));
		userPlayedGameRepository
			.saveAndFlush(UserPlayedGame.create(user.getId(), eligible.getId(), Instant.now()));
		userPlayedGameRepository
			.saveAndFlush(UserPlayedGame.create(user.getId(), wrongPlayerCount.getId(), Instant.now()));
		userPlayedGameRepository
			.saveAndFlush(UserPlayedGame.create(user.getId(), privateMechanism.getId(), Instant.now()));
		when(candidateSource.findCandidates(anyString())).thenReturn(List.of(
			new DenseCandidateSource.Candidate(unplayed.getId(), 0.99),
			new DenseCandidateSource.Candidate(wrongPlayerCount.getId(), 0.98),
			new DenseCandidateSource.Candidate(privateMechanism.getId(), 0.97),
			new DenseCandidateSource.Candidate(eligible.getId(), 0.96)));

		var result = semanticGameSearchService.search(query(user.getId(), 0, 10));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(eligible.getId()), result.content().stream().map(game -> game.id()).toList());
	}

	@Test
	void T2_중복과_동점은_필터뒤_relevance_name_id_순서와_페이지경계를_결정적으로_유지한다() {
		Game alpha = game(836_201L, "Alpha", 2, 4);
		Game beta = game(836_202L, "Beta", 2, 4);
		Game filtered = game(836_203L, "Filtered", 4, 4);
		when(candidateSource.findCandidates(anyString())).thenReturn(List.of(
			new DenseCandidateSource.Candidate(filtered.getId(), 1.0),
			new DenseCandidateSource.Candidate(beta.getId(), 0.9),
			new DenseCandidateSource.Candidate(alpha.getId(), 0.9),
			new DenseCandidateSource.Candidate(beta.getId(), 0.2)));

		var firstPage = semanticGameSearchService.search(query(null, 0, 1));
		var secondPage = semanticGameSearchService.search(query(null, 1, 1));

		assertEquals(SemanticGameSearchMode.SEMANTIC, firstPage.mode());
		assertEquals(List.of(alpha.getId()), firstPage.content().stream().map(game -> game.id()).toList());
		assertTrue(firstPage.hasNext());
		assertEquals(List.of(beta.getId()), secondPage.content().stream().map(game -> game.id()).toList());
		assertFalse(secondPage.hasNext());
		assertEquals(List.of(alpha.getId(), beta.getId()), List.of(firstPage, secondPage).stream()
			.flatMap(page -> page.content().stream()).map(game -> game.id()).toList());
	}

	@Test
	void T4_dense_실패뒤_동일한_P1_hard_filter와_playedFilter를_적용한_LEXICAL_FALLBACK만_반환한다() {
		GameCategory strategy = category("STRATEGY");
		GameMechanism publicMechanism = mechanism("WORKER_PLACEMENT", true);
		User user = user("t4");
		Game eligible = game(836_401L, "전략 협동 게임 eligible", 2, 4);
		Game unplayed = game(836_402L, "전략 협동 게임 unplayed", 2, 4);
		Game wrongPlayerCount = game(836_403L, "전략 협동 게임 wrong-player", 3, 4);
		Game privateMechanism = game(836_404L, "전략 협동 게임 private-mechanism", 2, 4);
		link(eligible, strategy, publicMechanism);
		link(unplayed, strategy, publicMechanism);
		link(wrongPlayerCount, strategy, publicMechanism);
		link(privateMechanism, strategy, mechanism("PRIVATE_T4", false));
		userPlayedGameRepository
			.saveAndFlush(UserPlayedGame.create(user.getId(), eligible.getId(), Instant.now()));
		userPlayedGameRepository
			.saveAndFlush(UserPlayedGame.create(user.getId(), wrongPlayerCount.getId(), Instant.now()));
		userPlayedGameRepository
			.saveAndFlush(UserPlayedGame.create(user.getId(), privateMechanism.getId(), Instant.now()));
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());

		var result = semanticGameSearchService.search(query(user.getId(), 0, 10));

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, result.mode());
		assertEquals(List.of(eligible.getId()), result.content().stream().map(game -> game.id()).toList());
	}

	@Test
	void T4_lexical_fallback은_P1_인기순과_DB_Slice_페이지경계를_유지한다() {
		Game lessPopular = game(836_501L, "전략 협동 게임 Alpha", 2, 4);
		Game popularFirstByName = game(836_502L, "전략 협동 게임 Bravo", 2, 4);
		Game popularSecondByName = game(836_503L, "전략 협동 게임 Charlie", 2, 4);
		ReflectionTestUtils.setField(lessPopular, "popularityScore", new BigDecimal("0.100000"));
		ReflectionTestUtils.setField(popularFirstByName, "popularityScore", new BigDecimal("0.200000"));
		ReflectionTestUtils.setField(popularSecondByName, "popularityScore", new BigDecimal("0.200000"));
		gameRepository.saveAllAndFlush(List.of(lessPopular, popularFirstByName, popularSecondByName));
		when(candidateSource.findCandidates(anyString())).thenThrow(new SemanticSearchUnavailableException());

		var firstPage = semanticGameSearchService.search(query(null, 0, 1));
		var secondPage = semanticGameSearchService.search(query(null, 1, 1));
		var thirdPage = semanticGameSearchService.search(query(null, 2, 1));

		assertEquals(SemanticGameSearchMode.LEXICAL_FALLBACK, firstPage.mode());
		assertEquals(List.of(popularFirstByName.getId()), firstPage.content().stream().map(game -> game.id()).toList());
		assertTrue(firstPage.hasNext());
		assertEquals(List.of(popularSecondByName.getId()),
			secondPage.content().stream().map(game -> game.id()).toList());
		assertTrue(secondPage.hasNext());
		assertEquals(List.of(lessPopular.getId()), thirdPage.content().stream().map(game -> game.id()).toList());
		assertFalse(thirdPage.hasNext());
	}

	@Test
	void T5_PostgreSQL에서_비공개_mechanism은_candidate_전에_VALIDATION_ERROR다() {
		GameMechanism privateMechanism = mechanism("PRIVATE_VALIDATION", false);
		SemanticGameSearchRequest request = new SemanticGameSearchRequest();
		request.setQuery("협력 게임");
		request.setMechanism(List.of(privateMechanism.getCode()));
		when(candidateSource.findCandidates(anyString())).thenReturn(List.of());

		BusinessException exception = assertThrows(BusinessException.class,
			() -> semanticGameSearchQueryService.search(request, null));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
		verifyNoInteractions(candidateSource);
	}

	private SemanticGameSearchQuery query(Long currentUserId, int page, int size) {
		GameListRequest request = new GameListRequest();
		request.setPlayerCount(2);
		if (currentUserId != null) {
			request.setCategory(List.of("STRATEGY"));
			request.setMechanism(List.of("WORKER_PLACEMENT"));
			request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));
		}
		GameListSearchCriteria criteria = GameListSearchCriteria.from(request);
		if (currentUserId != null) {
			criteria = criteria.withPlayedFilter(currentUserId);
		}
		return new SemanticGameSearchQuery("전략 협동 게임", criteria, page, size);
	}

	private GameCategory category(String code) {
		return gameCategoryRepository.saveAndFlush(new GameCategory(code, code, code, code, 1));
	}

	private GameMechanism mechanism(String code, boolean isPublic) {
		return gameMechanismRepository.saveAndFlush(
			new GameMechanism(
				800_000L + code.hashCode(),
				code,
				code,
				code,
				isPublic ? code + " 방식을 활용해요." : null,
				null,
				isPublic,
				"#836",
				isPublic ? "reviewer" : null,
				isPublic ? Instant.parse("2026-08-20T00:00:00Z") : null));
	}

	private User user(String suffix) {
		return userRepository.saveAndFlush(
			User.create("semantic-" + suffix + "@example.com", "{bcrypt}hash", "의미" + suffix));
	}

	private Game game(long bggId, String name, int minPlayers, int maxPlayers) {
		Game game = new Game(bggId, name, name, "2~4명", "전략", "30분", "설명", "상세 설명");
		ReflectionTestUtils.setField(game, "minPlayers", minPlayers);
		ReflectionTestUtils.setField(game, "maxPlayers", maxPlayers);
		ReflectionTestUtils.setField(game, "complexity", new BigDecimal("2.00"));
		return gameRepository.saveAndFlush(game);
	}

	private void link(Game game, GameCategory category, GameMechanism mechanism) {
		gameCategoryRelationRepository.saveAndFlush(new GameCategoryRelation(game, category));
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(game, mechanism));
	}
}
