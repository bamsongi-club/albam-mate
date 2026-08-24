package cloud.bamsongi.albammate.infra.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
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
import cloud.bamsongi.albammate.testsupport.PostgresDatabaseCleaner;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * mechanism/category/name/description 계열로 구조화된 sparse 후보를 만드는
 * {@link StructuredSparseCandidateSource}를 실제 PostgreSQL에서 검증한다(#983 T1).
 */
@Testcontainers
@SpringBootTest
@Transactional
class StructuredSparseCandidateSourcePostgresTest extends SharedPostgresIntegrationSupport {

	private static final String ISSUE_1053_FIXTURE_PROPERTY = "issue1053.fixture";
	private static final String ISSUE_1053_FIXTURE_SHA256 = "7866812e8ecd22942eccc3dee4553b49161af6297399c907b6a2953a9abb3c19";
	private static final long ISSUE_1053_FIXTURE_BYTES = 206_704_274L;
	private static final long ISSUE_1053_CATALOG_ROWS = 170_005L;
	private static final int ISSUE_1053_MEASUREMENT_REPETITIONS = 5;
	private static final List<String> ISSUE_1053_TRIGRAM_INDEXES = List.of("ix_games_english_name_lower_trgm",
		"ix_games_alias_lower_trgm", "ix_games_description_lower_trgm");
	private static final List<String> ISSUE_1053_BIGRAM_INDEXES = List.of("ix_games_name_lower_bigram",
		"ix_games_english_name_lower_bigram", "ix_games_alias_lower_bigram", "ix_games_description_lower_bigram");

	@Autowired
	private DataSource dataSource;
	@Autowired
	private JdbcTemplate jdbcTemplate;
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
	void T1_Forward_Flyway가_pg_trgm과_sparse_검색_GIN_인덱스를_생성한다() {
		assertEquals(1, jdbc("""
			select count(*) from flyway_schema_history
			where version = '39' and success = true and script = 'V39__add_game_sparse_search_trigram_indexes.sql'
			"""));
		assertEquals(1, jdbc("select count(*) from pg_extension where extname = 'pg_trgm'"));
		assertEquals(1, jdbc("select count(*) from pg_indexes where indexname = 'ix_games_name_lower_trgm'"));
		assertSparseTextIndexesExist();
		assertIndexDefinition("ix_games_english_name_lower_trgm", "english_name");
		assertIndexDefinition("ix_games_alias_lower_trgm", "alias");
		assertIndexDefinition("ix_games_description_lower_trgm", "description");
		assertBigramIndexDefinition("ix_games_name_lower_bigram", "name");
		assertBigramIndexDefinition("ix_games_english_name_lower_bigram", "english_name");
		assertBigramIndexDefinition("ix_games_alias_lower_bigram", "alias");
		assertBigramIndexDefinition("ix_games_description_lower_bigram", "description");
	}

	@Test
	void T2_name_matches는_OR_세_경로에서_GIN_인덱스를_사용하고_games_Seq_Scan으로_회귀하지_않는다() throws Exception {
		assertSparseTextIndexesExist();
		String token = "issue1053namepath";
		Game name = game(1_053_201L, token + "-ko", "unrelated english", "설명 없음");
		Game englishName = game(1_053_202L, "무관한 이름", token + "-en", "설명 없음");
		Game alias = game(1_053_203L, "별칭 게임", "Alias Game", "설명 없음");
		jdbcTemplate.update("update games set alias = ? where id = ?", token + "-alias", alias.getId());

		String plan = explainSparseServingSql(token, true);

		assertTrue(source().findCandidates(token).stream().map(DenseCandidateSource.Candidate::gameId).toList()
			.containsAll(List.of(name.getId(), englishName.getId(), alias.getId())));
		assertTrue(plan.contains("ix_games_english_name_lower_trgm"), plan);
		assertTrue(plan.contains("ix_games_alias_lower_trgm"), plan);
		assertTrue(!plan.contains("Seq Scan on games"), plan);
	}

	@Test
	void T3_description_matches는_GIN_인덱스를_사용하고_games_Seq_Scan으로_회귀하지_않는다() throws Exception {
		assertSparseTextIndexesExist();
		String token = "issue1053descriptionpath";
		Game description = game(1_053_301L, "설명 인덱스 게임", "Description Index Game", token + "을 포함한 설명");

		String plan = explainSparseServingSql(token, true);

		assertTrue(source().findCandidates(token).stream().map(DenseCandidateSource.Candidate::gameId).toList()
			.contains(description.getId()));
		assertTrue(plan.contains("ix_games_description_lower_trgm"), plan);
		assertTrue(!plan.contains("Seq Scan on games"), plan);
	}

	@Test
	void T4_이름_영문명_별칭_설명_점수_정렬_후보상한_no_result와_deadline_계약을_보존한다() throws Exception {
		assertSparseTextIndexesExist();
		String token = "issue1054regressiontoken";
		Game byName = game(1_053_401L, token + " 이름", "Name Game", "설명 없음");
		Game byEnglishName = game(1_053_402L, "영문명 게임", token + " English", "설명 없음");
		Game byAlias = game(1_053_403L, "별칭 게임", "Alias Game", "설명 없음");
		Game byDescription = game(1_053_404L, "설명 게임", "Description Game", token + " 설명");
		Game nameAndDescription = game(1_053_405L, token + " 이름설명", "Combined Game", token + " 설명");
		jdbcTemplate.update("update games set alias = ? where id = ?", token + " Alias", byAlias.getId());

		List<DenseCandidateSource.Candidate> fieldCandidates = source().findCandidates(token);
		List<Long> fieldCandidateIds = fieldCandidates.stream().map(DenseCandidateSource.Candidate::gameId).toList();
		assertTrue(fieldCandidateIds.containsAll(List.of(byName.getId(), byEnglishName.getId(), byAlias.getId(),
			byDescription.getId(), nameAndDescription.getId())), fieldCandidateIds.toString());
		assertEquals(nameAndDescription.getId(), fieldCandidates.getFirst().gameId());
		assertEquals(3.0, relevance(fieldCandidates, byName.getId()), 0.000_001);
		assertEquals(3.0, relevance(fieldCandidates, byEnglishName.getId()), 0.000_001);
		assertEquals(3.0, relevance(fieldCandidates, byAlias.getId()), 0.000_001);
		assertEquals(1.0, relevance(fieldCandidates, byDescription.getId()), 0.000_001);
		assertEquals(4.0, relevance(fieldCandidates, nameAndDescription.getId()), 0.000_001);
		assertTrue(fieldCandidateIds.indexOf(byName.getId()) < fieldCandidateIds.indexOf(byEnglishName.getId()));
		assertTrue(fieldCandidateIds.indexOf(byEnglishName.getId()) < fieldCandidateIds.indexOf(byAlias.getId()));

		Game shortTokenGame = game(1_053_407L, "두글고유 게임", "Unique Two Character Game", "설명 없음");
		assertTrue(source().findCandidates("글고").stream().map(DenseCandidateSource.Candidate::gameId)
			.toList().contains(shortTokenGame.getId()));

		String limitToken = "issue1054limittoken";
		Game highestScore = game(1_053_406L, limitToken + " 고득점", "High Score Game", limitToken + " 설명");
		for (int index = 0; index < 201; index++) {
			game(1_053_500L + index, limitToken + " 후보 " + index, "Candidate " + index, "설명 없음");
		}

		List<DenseCandidateSource.Candidate> candidates = source().findCandidates(limitToken);
		assertEquals(200, candidates.size());
		assertEquals(highestScore.getId(), candidates.getFirst().gameId());
		assertThrows(SemanticSearchUnavailableException.class,
			() -> source().findCandidates("issue1053noresulttoken"));
	}

	@Test
	@Tag("measurement")
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void T5_승인된_십칠만오건_fixture에서_migration_전후_sparse_실행계획과_육초_deadline을_측정한다() throws Exception {
		Path fixture = Path.of(requiredIssue1053Fixture());
		assertEquals(ISSUE_1053_FIXTURE_BYTES, Files.size(fixture));
		assertEquals(ISSUE_1053_FIXTURE_SHA256, sha256(fixture));
		assertSparseTextIndexesExist();

		boolean databaseMutated = false;
		try {
			databaseMutated = true;
			jdbcTemplate.execute("truncate table games restart identity cascade");
			dropSparseIndexes();
			try (Connection connection = dataSource.getConnection()) {
				try (Statement statement = connection.createStatement()) {
					statement.execute(Files.readString(fixture, StandardCharsets.UTF_8));
				}
			}
			assertEquals(ISSUE_1053_CATALOG_ROWS,
				jdbcTemplate.queryForObject("select count(*) from games", Long.class));
			jdbcTemplate.execute("analyze games");

			String baselinePlan = explainSparseServingSql("rejuvenation", false);
			List<Double> baselineMillis = measureSparseServingSql("rejuvenation");
			long migrationStartedAtNanos = System.nanoTime();
			applySparseIndexMigration();
			double migrationMillis = (System.nanoTime() - migrationStartedAtNanos) / 1_000_000.0;
			String candidatePlan = explainSparseServingSql("rejuvenation", false);
			List<Double> candidateMillis = measureSparseServingSql("rejuvenation");
			String shortTokenPlan = explainSparseServingSql("게임", false);
			List<Double> shortTokenMillis = measureSparseServingSql("게임");

			assertTrue(baselinePlan.contains("Seq Scan on games"), baselinePlan);
			assertTrue(candidatePlan.contains("ix_games_name_lower_trgm"), candidatePlan);
			assertTrue(candidatePlan.contains("ix_games_english_name_lower_trgm"), candidatePlan);
			assertTrue(candidatePlan.contains("ix_games_alias_lower_trgm"), candidatePlan);
			assertTrue(candidatePlan.contains("ix_games_description_lower_trgm"), candidatePlan);
			assertTrue(!candidatePlan.contains("Seq Scan on games"), candidatePlan);
			assertTrue(percentile95(candidateMillis) <= percentile95(baselineMillis),
				"candidate p95=" + percentile95(candidateMillis) + ", baseline p95=" + percentile95(baselineMillis));
			assertTrue(max(candidateMillis) < Duration.ofSeconds(6).toMillis(),
				"candidate max=" + max(candidateMillis));
			assertTrue(shortTokenPlan.contains("ix_games_name_lower_bigram"), shortTokenPlan);
			assertTrue(shortTokenPlan.contains("ix_games_english_name_lower_bigram"), shortTokenPlan);
			assertTrue(shortTokenPlan.contains("ix_games_alias_lower_bigram"), shortTokenPlan);
			assertTrue(shortTokenPlan.contains("ix_games_description_lower_bigram"), shortTokenPlan);
			assertTrue(!shortTokenPlan.contains("Seq Scan on games"), shortTokenPlan);
			assertTrue(max(shortTokenMillis) < Duration.ofSeconds(6).toMillis(),
				"short-token max=" + max(shortTokenMillis));
			System.out.printf(Locale.ROOT,
				"ISSUE_1053_T5 fixtureSha256=%s rows=%d migrationMs=%.3f baselineMs=%s baselineP50=%.3f baselineP95=%.3f baselineMax=%.3f candidateMs=%s candidateP50=%.3f candidateP95=%.3f candidateMax=%.3f shortTokenMs=%s shortTokenP50=%.3f shortTokenP95=%.3f shortTokenMax=%.3f%n",
				ISSUE_1053_FIXTURE_SHA256, ISSUE_1053_CATALOG_ROWS, migrationMillis, baselineMillis,
				percentile50(baselineMillis),
				percentile95(baselineMillis), max(baselineMillis), candidateMillis, percentile50(candidateMillis),
				percentile95(candidateMillis), max(candidateMillis), shortTokenMillis, percentile50(shortTokenMillis),
				percentile95(shortTokenMillis), max(shortTokenMillis));
		} finally {
			if (databaseMutated) {
				restoreIssue1053Database();
			}
		}
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

	@Test
	void T1_비공개_메커니즘_이름만으로는_게임이_검색되지_않는다() {
		GameMechanism privateMechanism = mechanism("SPARSE_T1_PRIVATE_MECH", "비공개메커니즘고유어휘",
			"PrivateMechanismUniqueTerm", false);
		Game privateGame = game(983_401L, "비공개메커니즘게임", "Private Mechanism Game", "설명 없음");
		link(privateGame, privateMechanism);

		assertThrows(SemanticSearchUnavailableException.class,
			() -> source().findCandidates("비공개메커니즘고유어휘"));
	}

	@Test
	void ISSUE_1001_T1_남은_deadline을_statement_timeout으로_적용해_잠긴_조회가_취소된다() throws Exception {
		try (Connection lockConnection = dataSource.getConnection();
			Statement lockStatement = lockConnection.createStatement()) {
			lockConnection.setAutoCommit(false);
			lockStatement.execute("lock table games in access exclusive mode");

			long startedAtNanos = System.nanoTime();
			assertThrows(SemanticSearchUnavailableException.class,
				() -> source().findCandidates("timeout 고유 토큰", Duration.ofMillis(200)));
			long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000;

			assertTrue(elapsedMillis < 1_000,
				"잠긴 sparse JDBC 조회는 공통 deadline에 맞춰 취소되어 1초 statement timeout까지 기다리면 안 됩니다.");
			lockConnection.rollback();
			assertEquals(0, waitingSparseQueries(), "timeout 뒤 잠긴 sparse JDBC 작업이 남아 있으면 안 됩니다.");
		}
	}

	@Test
	void ISSUE_1001_T1_statement_timeout은_남은_deadline을_초단위로_설정한다() throws Exception {
		JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
		PreparedStatement statement = org.mockito.Mockito.mock(PreparedStatement.class);
		org.mockito.Mockito.when(jdbcTemplate.query(org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.any(PreparedStatementSetter.class),
			org.mockito.ArgumentMatchers.any(RowMapper.class))).thenAnswer(invocation -> {
				PreparedStatementSetter setter = invocation.getArgument(1);
				setter.setValues(statement);
				return List.of(new DenseCandidateSource.Candidate(1L, 1.0));
			});

		new StructuredSparseCandidateSource(jdbcTemplate).findCandidates("timeout 테스트", Duration.ofMillis(200));

		org.mockito.Mockito.verify(statement).setQueryTimeout(1);
	}

	private StructuredSparseCandidateSource source() {
		return new StructuredSparseCandidateSource(new JdbcTemplate(dataSource));
	}

	private int jdbc(String sql) {
		return jdbcTemplate.queryForObject(sql, Integer.class);
	}

	private void assertSparseTextIndexesExist() {
		for (String index : ISSUE_1053_TRIGRAM_INDEXES) {
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from pg_indexes where schemaname = 'public' and indexname = ?", Integer.class, index));
		}
		for (String index : ISSUE_1053_BIGRAM_INDEXES) {
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from pg_indexes where schemaname = 'public' and indexname = ?", Integer.class, index));
		}
	}

	private void assertIndexDefinition(String index, String expression) {
		String definition = jdbcTemplate.queryForObject("select pg_get_indexdef(?::regclass)", String.class, index);
		assertTrue(definition.contains("USING gin"), definition);
		assertTrue(definition.contains("lower"), definition);
		assertTrue(definition.contains(expression), definition);
		assertTrue(definition.contains("gin_trgm_ops"), definition);
	}

	private void assertBigramIndexDefinition(String index, String expression) {
		String definition = jdbcTemplate.queryForObject("select pg_get_indexdef(?::regclass)", String.class, index);
		assertTrue(definition.contains("USING gin"), definition);
		assertTrue(definition.contains("game_search_bigrams"), definition);
		assertTrue(definition.contains(expression), definition);
	}

	private String explainSparseServingSql(String token, boolean disableSequentialScan) throws Exception {
		String sql = sparseServingSql();
		return jdbcTemplate.execute((ConnectionCallback<String>)connection -> {
			if (disableSequentialScan) {
				try (Statement statement = connection.createStatement()) {
					statement.execute("set enable_seqscan = off");
				}
			}
			try (PreparedStatement statement = connection.prepareStatement("explain (analyze, buffers) " + sql)) {
				statement.setString(1, token);
				statement.setInt(2, 200);
				try (ResultSet resultSet = statement.executeQuery()) {
					StringBuilder plan = new StringBuilder();
					while (resultSet.next()) {
						plan.append(resultSet.getString(1)).append('\n');
					}
					return plan.toString();
				}
			}
		});
	}

	private String sparseServingSql() throws Exception {
		Method sql = StructuredSparseCandidateSource.class.getDeclaredMethod("sql", int.class);
		sql.setAccessible(true);
		return (String)sql.invoke(source(), 1);
	}

	private double relevance(List<DenseCandidateSource.Candidate> candidates, long gameId) {
		return candidates.stream().filter(candidate -> candidate.gameId() == gameId).findFirst().orElseThrow()
			.relevance();
	}

	private String requiredIssue1053Fixture() {
		String fixture = System.getProperty(ISSUE_1053_FIXTURE_PROPERTY);
		if (fixture == null || fixture.isBlank()) {
			fixture = System.getenv("ISSUE1053_FIXTURE");
		}
		assumeTrue(fixture != null && !fixture.isBlank(),
			"-D" + ISSUE_1053_FIXTURE_PROPERTY + " 또는 ISSUE1053_FIXTURE가 필요합니다");
		return fixture;
	}

	private String sha256(Path path) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int read; (read = input.read(buffer)) != -1;) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private void dropSparseIndexes() {
		for (String index : ISSUE_1053_TRIGRAM_INDEXES) {
			jdbcTemplate.execute("drop index " + index);
		}
		for (String index : ISSUE_1053_BIGRAM_INDEXES) {
			jdbcTemplate.execute("drop index " + index);
		}
	}

	private void applySparseIndexMigration() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			try (Statement statement = connection.createStatement()) {
				statement.execute(Files.readString(Path.of(
					"src/main/resources/db/vendor-migration/postgresql/V39__add_game_sparse_search_trigram_indexes.sql"),
					StandardCharsets.UTF_8));
			}
		}
	}

	private void restoreIssue1053Database() throws Exception {
		PostgresDatabaseCleaner.clean(dataSource);
		jdbcTemplate.execute("create extension if not exists pg_trgm");
		ensureSparseBigramFunction();
		jdbcTemplate.execute("""
			create index if not exists ix_games_english_name_lower_trgm
			    on games using gin (lower(english_name) gin_trgm_ops)
			""");
		jdbcTemplate.execute("""
			create index if not exists ix_games_alias_lower_trgm
			    on games using gin (lower(alias) gin_trgm_ops)
			""");
		jdbcTemplate.execute("""
			create index if not exists ix_games_description_lower_trgm
			    on games using gin (lower(description) gin_trgm_ops)
			""");
		jdbcTemplate.execute("""
			create index if not exists ix_games_name_lower_bigram
			    on games using gin (game_search_bigrams(name))
			""");
		jdbcTemplate.execute("""
			create index if not exists ix_games_english_name_lower_bigram
			    on games using gin (game_search_bigrams(english_name))
			""");
		jdbcTemplate.execute("""
			create index if not exists ix_games_alias_lower_bigram
			    on games using gin (game_search_bigrams(alias))
			""");
		jdbcTemplate.execute("""
			create index if not exists ix_games_description_lower_bigram
			    on games using gin (game_search_bigrams(description))
			""");
	}

	private void ensureSparseBigramFunction() {
		jdbcTemplate.execute("""
			create or replace function game_search_bigrams(value text)
			returns text[]
			language plpgsql
			immutable
			parallel safe
			as $$
			declare
				normalized text := lower(coalesce(value, ''));
				grams text[] := array[]::text[];
				position integer;
			begin
				if char_length(normalized) < 2 then
					return grams;
				end if;
				for position in 1..char_length(normalized) - 1 loop
					grams := array_append(grams, substring(normalized from position for 2));
				end loop;
				return grams;
			end;
			$$
			""");
	}

	private List<Double> measureSparseServingSql(String query) {
		List<Double> measurements = new ArrayList<>();
		for (int index = 0; index < ISSUE_1053_MEASUREMENT_REPETITIONS; index++) {
			long startedAtNanos = System.nanoTime();
			source().findCandidates(query, Duration.ofSeconds(6));
			measurements.add((System.nanoTime() - startedAtNanos) / 1_000_000.0);
		}
		return measurements;
	}

	private double percentile50(List<Double> values) {
		return percentile(values, 0.50);
	}

	private double percentile95(List<Double> values) {
		return percentile(values, 0.95);
	}

	private double percentile(List<Double> values, double percentile) {
		List<Double> sorted = values.stream().sorted().toList();
		return sorted.get((int)Math.ceil(percentile * sorted.size()) - 1);
	}

	private double max(List<Double> values) {
		return values.stream().max(Comparator.naturalOrder()).orElseThrow();
	}

	private int waitingSparseQueries() throws Exception {
		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement();
			ResultSet resultSet = statement.executeQuery("""
				select count(*) from pg_stat_activity
				where datname = current_database() and wait_event_type = 'Lock' and query like '%%with tokens%%'
				""")) {
			resultSet.next();
			return resultSet.getInt(1);
		}
	}

	private GameCategory category(String code) {
		return gameCategoryRepository.saveAndFlush(new GameCategory(code, code, code, code, 1));
	}

	private GameMechanism mechanism(String code, String nameKo, String nameEn) {
		return mechanism(code, nameKo, nameEn, true);
	}

	private GameMechanism mechanism(String code, String nameKo, String nameEn, boolean isPublic) {
		return gameMechanismRepository.saveAndFlush(
			new GameMechanism(900_000L + Math.abs(code.hashCode()), code, nameKo, nameEn, code + " 방식을 활용해요.", null,
				isPublic, "#983", "reviewer", java.time.Instant.parse("2026-08-22T00:00:00Z")));
	}

	private Game game(long bggId, String name, String englishName, String description) {
		return gameRepository.saveAndFlush(
			new Game(bggId, name, englishName, "2~4명", "전략", "30분", description, description));
	}

	private void link(Game game, GameMechanism mechanism) {
		gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(game, mechanism));
	}
}
