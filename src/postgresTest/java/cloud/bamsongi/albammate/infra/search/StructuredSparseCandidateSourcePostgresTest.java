package cloud.bamsongi.albammate.infra.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameCategory;
import cloud.bamsongi.albammate.game.entity.GameCategoryRelation;
import cloud.bamsongi.albammate.game.entity.GameMechanism;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.repository.GameCategoryRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * mechanism/category/name/description 계열로 구조화된 sparse 후보를 만드는
 * {@link StructuredSparseCandidateSource}를 실제 PostgreSQL에서 검증한다(#983 T1).
 */
@Testcontainers
@SpringBootTest
@Transactional
class StructuredSparseCandidateSourcePostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private DataSource dataSource;
	@Autowired
	private GameRepository gameRepository;
	@Autowired
	private GameMechanismRepository gameMechanismRepository;
	@Autowired
	private GameMechanismRelationRepository gameMechanismRelationRepository;
	@Autowired
	private GameCategoryRepository gameCategoryRepository;
	@Autowired
	private GameCategoryRelationRepository gameCategoryRelationRepository;

	@Test
	void T1_정상_입력에서_결정적_후보목록을_반환한다() {
		GameMechanism workerPlacement = mechanism("SPARSE_T1_WORKER_PLACEMENT", "일꾼놓기고유어휘",
			"WorkerPlacementUniqueTerm");
		Game stoneAge = game(983_101L, "돌의시대고유명", "StoneAgeUniqueName",
			"일꾼놓기고유어휘를 활용해 자원을 모으는 내용입니다.");
		Game unrelated = game(983_102L, "관련없는게임", "Unrelated Game", "카드를 내는 게임입니다.");
		link(stoneAge, workerPlacement);

		List<DenseCandidateSource.Candidate> firstCall = source().findCandidates("일꾼놓기고유어휘");
		List<DenseCandidateSource.Candidate> secondCall = source().findCandidates("일꾼놓기고유어휘");

		assertEquals(List.of(stoneAge.getId()), firstCall.stream().map(DenseCandidateSource.Candidate::gameId)
			.filter(id -> id.equals(stoneAge.getId()) || id.equals(unrelated.getId())).toList());
		assertEquals(firstCall, secondCall);
	}

	@Test
	void T1_매칭되는_후보가_없으면_SemanticSearchUnavailableException을_던진다() {
		game(983_201L, "무관한 게임", "Irrelevant Game", "설명");

		assertThrows(SemanticSearchUnavailableException.class,
			() -> source().findCandidates("존재하지않는고유토큰조합zzzzz"));
	}

	@Test
	void T1_의미있는_토큰이_없으면_SemanticSearchUnavailableException을_던진다() {
		assertThrows(SemanticSearchUnavailableException.class, () -> source().findCandidates("a "));
	}

	@Test
	void T1_이름_별칭_설명_카테고리_메커니즘_필드_모두에서_후보를_찾는다() {
		GameCategory strategy = category("SPARSE_T1_STRATEGY");
		GameMechanism mechanism = mechanism("SPARSE_T1_DECK", "덱빌딩", "Deck Building");
		Game byName = game(983_301L, "밥먹는용사", "Rice Warrior", "설명 없음");
		Game byCategory = game(983_302L, "카테고리게임", "Category Game", "설명 없음");
		Game byMechanism = game(983_303L, "메커니즘게임", "Mechanism Game", "설명 없음");
		Game byDescription = game(983_304L, "설명게임", "Description Game", "밥을 먹이는 내용입니다.");
		gameCategoryRelationRepository.saveAndFlush(new GameCategoryRelation(byCategory, strategy));
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(byMechanism, mechanism));

		List<Long> nameMatches = source().findCandidates("밥먹는").stream()
			.map(DenseCandidateSource.Candidate::gameId).toList();
		List<Long> categoryMatches = source().findCandidates("SPARSE_T1_STRATEGY").stream()
			.map(DenseCandidateSource.Candidate::gameId).toList();
		List<Long> mechanismMatches = source().findCandidates("덱빌딩").stream()
			.map(DenseCandidateSource.Candidate::gameId).toList();
		List<Long> descriptionMatches = source().findCandidates("밥을 먹이는").stream()
			.map(DenseCandidateSource.Candidate::gameId).toList();

		assertTrue(nameMatches.contains(byName.getId()));
		assertTrue(categoryMatches.contains(byCategory.getId()));
		assertTrue(mechanismMatches.contains(byMechanism.getId()));
		assertTrue(descriptionMatches.contains(byDescription.getId()));
	}

	private StructuredSparseCandidateSource source() {
		return new StructuredSparseCandidateSource(new JdbcTemplate(dataSource));
	}

	private GameCategory category(String code) {
		return gameCategoryRepository.saveAndFlush(new GameCategory(code, code, code, code, 1));
	}

	private GameMechanism mechanism(String code, String nameKo, String nameEn) {
		return gameMechanismRepository.saveAndFlush(
			new GameMechanism(900_000L + Math.abs(code.hashCode()), code, nameKo, nameEn, code + " 방식을 활용해요.", null,
				true, "#983", "reviewer", java.time.Instant.parse("2026-08-22T00:00:00Z")));
	}

	private Game game(long bggId, String name, String englishName, String description) {
		return gameRepository.saveAndFlush(
			new Game(bggId, name, englishName, "2~4명", "전략", "30분", description, description));
	}

	private void link(Game game, GameMechanism mechanism) {
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(game, mechanism));
	}
}
