package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
import cloud.bamsongi.albammate.game.contract.SparseCandidateSource;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
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
import cloud.bamsongi.albammate.game.service.SemanticGameSearchService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

/**
 * dense·sparse candidate가 모두 성공해 RRF로 결합될 때도 기존 P1 hard filter·playedFilter·결정적
 * pagination이 그대로 재검증되는지 확인한다(#983 T5, ADR-0088).
 */
@Testcontainers
@SpringBootTest
@Transactional
class HybridSemanticGameSearchPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private SemanticGameSearchService semanticGameSearchService;
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

	@Test
	void T5_dense와_sparse가_모두_성공해도_P1_hard_filter와_playedFilter를_재검증한다() {
		GameCategory strategy = category("HYBRID_STRATEGY");
		GameMechanism publicMechanism = mechanism("HYBRID_WORKER_PLACEMENT", true);
		User user = user("hybrid-t5");
		Game eligible = game(983_501L, "Eligible", 2, 4);
		Game wrongPlayerCount = game(983_502L, "Wrong-player", 3, 4);
		Game privateMechanism = game(983_503L, "Private-mechanism", 2, 4);
		link(eligible, strategy, publicMechanism);
		link(wrongPlayerCount, strategy, publicMechanism);
		link(privateMechanism, strategy, mechanism("HYBRID_PRIVATE", false));
		userPlayedGameRepository.saveAndFlush(UserPlayedGame.create(user.getId(), eligible.getId(), Instant.now()));
		userPlayedGameRepository
			.saveAndFlush(UserPlayedGame.create(user.getId(), wrongPlayerCount.getId(), Instant.now()));
		userPlayedGameRepository
			.saveAndFlush(UserPlayedGame.create(user.getId(), privateMechanism.getId(), Instant.now()));
		when(candidateSource.findCandidates(anyString())).thenReturn(List.of(
			new DenseCandidateSource.Candidate(wrongPlayerCount.getId(), 0.99),
			new DenseCandidateSource.Candidate(eligible.getId(), 0.90)));
		when(sparseCandidateSource.findCandidates(anyString())).thenReturn(List.of(
			new DenseCandidateSource.Candidate(privateMechanism.getId(), 6.0),
			new DenseCandidateSource.Candidate(eligible.getId(), 5.0)));

		var result = semanticGameSearchService.search(query(user.getId(), 0, 10));

		assertEquals(SemanticGameSearchMode.SEMANTIC, result.mode());
		assertEquals(List.of(eligible.getId()), result.content().stream().map(game -> game.id()).toList());
	}

	@Test
	void T5_dense와_sparse가_모두_성공하면_동일입력에서_결정적_pagination을_유지한다() {
		Game alpha = game(983_601L, "Alpha", 2, 4);
		Game beta = game(983_602L, "Beta", 2, 4);
		Game filtered = game(983_603L, "Filtered", 4, 4);
		when(candidateSource.findCandidates(anyString())).thenReturn(List.of(
			new DenseCandidateSource.Candidate(filtered.getId(), 1.0),
			new DenseCandidateSource.Candidate(alpha.getId(), 0.9)));
		when(sparseCandidateSource.findCandidates(anyString()))
			.thenReturn(List.of(new DenseCandidateSource.Candidate(beta.getId(), 6.0)));

		var firstPage = semanticGameSearchService.search(query(null, 0, 1));
		var secondPage = semanticGameSearchService.search(query(null, 1, 1));
		var thirdPage = semanticGameSearchService.search(query(null, 0, 1));

		assertEquals(SemanticGameSearchMode.SEMANTIC, firstPage.mode());
		assertTrue(firstPage.hasNext());
		assertFalse(secondPage.hasNext());
		assertEquals(firstPage.content(), thirdPage.content());
		assertEquals(2, firstPage.content().size() + secondPage.content().size());
	}

	private SemanticGameSearchQuery query(Long currentUserId, int page, int size) {
		GameListRequest request = new GameListRequest();
		request.setPlayerCount(2);
		if (currentUserId != null) {
			request.setCategory(List.of("HYBRID_STRATEGY"));
			request.setMechanism(List.of("HYBRID_WORKER_PLACEMENT"));
			request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));
		}
		GameListSearchCriteria criteria = GameListSearchCriteria.from(request);
		if (currentUserId != null) {
			criteria = criteria.withPlayedFilter(currentUserId);
		}
		return new SemanticGameSearchQuery("일꾼 놓기 게임", criteria, page, size);
	}

	private GameCategory category(String code) {
		return gameCategoryRepository.saveAndFlush(new GameCategory(code, code, code, code, 1));
	}

	private GameMechanism mechanism(String code, boolean isPublic) {
		return gameMechanismRepository.saveAndFlush(
			new GameMechanism(
				800_000L + Math.abs(code.hashCode()),
				code,
				code,
				code,
				isPublic ? code + " 방식을 활용해요." : null,
				null,
				isPublic,
				"#983",
				isPublic ? "reviewer" : null,
				isPublic ? Instant.parse("2026-08-22T00:00:00Z") : null));
	}

	private User user(String suffix) {
		return userRepository.saveAndFlush(
			User.create("hybrid-" + suffix + "@example.com", "{bcrypt}hash", "하이브리드" + suffix));
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
