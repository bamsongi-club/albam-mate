package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;

@Testcontainers
@SpringBootTest
class AssistantGameCandidatePostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final Instant NOW = Instant.parse("2099-01-01T00:00:00Z");
	private static final OffsetDateTime NOW_UTC = NOW.atOffset(ZoneOffset.UTC);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("assistant_game_candidate_test");

	@Autowired
	private AssistantGameCandidateQuery candidateQuery;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private long hostUserId;
	private long strategyCategoryId;
	private long cooperativeMechanismId;
	private long hiddenCooperativeMechanismId;
	private long fantasyThemeId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '방장', ?, ?)",
			"assistant-candidate-postgres@example.com", NOW_UTC, NOW_UTC);
		hostUserId = jdbcTemplate.queryForObject(
			"select id from users where email = 'assistant-candidate-postgres@example.com'", Long.class);
		jdbcTemplate.update(
			"insert into game_categories (code, name_ko, name_en, bgg_subdomain, display_order, created_at, updated_at) values ('STRATEGY', '전략', 'Strategy', 'strategygames', 1, ?, ?)",
			NOW_UTC, NOW_UTC);
		strategyCategoryId = jdbcTemplate.queryForObject(
			"select id from game_categories where code = 'STRATEGY'", Long.class);
		jdbcTemplate.update(
			"insert into game_mechanisms (bgg_mechanism_id, code, name_ko, name_en, description_ko, featured_order, is_public, source_reference, reviewed_by, reviewed_at, created_at, updated_at) values (1001, 'COOPERATIVE', '협력', 'Cooperative', '공개 협력 메커니즘', 1, true, 'test', 'reviewer', ?, ?, ?)",
			NOW_UTC, NOW_UTC, NOW_UTC);
		cooperativeMechanismId = jdbcTemplate.queryForObject(
			"select id from game_mechanisms where code = 'COOPERATIVE'", Long.class);
		jdbcTemplate.update(
			"insert into game_mechanisms (bgg_mechanism_id, code, name_ko, name_en, source_reference, created_at, updated_at, is_public) values (1002, 'HIDDEN_COOPERATIVE', '비공개 협력', 'Hidden cooperative', 'test', ?, ?, false)",
			NOW_UTC, NOW_UTC);
		hiddenCooperativeMechanismId = jdbcTemplate.queryForObject(
			"select id from game_mechanisms where code = 'HIDDEN_COOPERATIVE'", Long.class);
		jdbcTemplate.update(
			"insert into game_themes (bgg_theme_id, code, name_ko, name_en, created_at, updated_at) values (2001, 'FANTASY', '판타지', 'Fantasy', ?, ?)",
			NOW_UTC, NOW_UTC);
		fantasyThemeId = jdbcTemplate.queryForObject(
			"select id from game_themes where code = 'FANTASY'", Long.class);
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table rooms, game_category_relations, game_mechanism_relations, game_theme_relations, game_categories, game_mechanisms, game_themes, games, users restart identity cascade");
	}

	@Test
	void T4_PostgreSQL_후보DTO도_AND_RANK_01_상위10개와_공개필드를_반환한다() {
		long highest = insertGame("가장 인기", true);
		long tied = insertGame("동률 인기", true);
		for (int index = 0; index < 10; index++) {
			insertGame("0건 후보 " + index, true);
		}
		for (int index = 0; index < 3; index++) {
			insertRoom(highest, index);
			insertRoom(tied, index + 10);
		}

		var result = candidateQuery.findCandidates(
			new AssistantGameCandidateQuery.Criteria(List.of("STRATEGY")));

		assertEquals(10, result.size());
		assertEquals(List.of(highest, tied), result.subList(0, 2).stream().map(candidate -> candidate.id()).toList());
		assertEquals("설명", result.getFirst().description());
		assertEquals(null, result.getFirst().imageUrl());
	}

	@Test
	void T5_PostgreSQL_카테고리_후보만_집계하고_RANK_01_동률_ID순서와_0건_보완_상위10개를_적용한다() {
		long highest = insertGame("가장 인기", true);
		long tied = insertGame("동률 인기", true);
		List<Long> zeroCandidates = new ArrayList<>();
		for (int index = 0; index < 10; index++) {
			zeroCandidates.add(insertGame("0건 후보 " + index, true));
		}
		long excluded = insertGame("후보 밖", false);
		for (int index = 0; index < 3; index++) {
			insertRoom(highest, index);
			insertRoom(tied, index + 10);
		}
		for (int index = 0; index < 99; index++) {
			insertRoom(excluded, index + 100);
		}

		var result = candidateQuery.findCandidates(new AssistantGameCandidateQuery.Criteria(List.of("STRATEGY")));

		assertEquals(List.of(highest, tied, zeroCandidates.get(0), zeroCandidates.get(1), zeroCandidates.get(2),
			zeroCandidates.get(3), zeroCandidates.get(4), zeroCandidates.get(5), zeroCandidates.get(6),
			zeroCandidates.get(7)),
			result.stream().map(candidate -> candidate.id()).toList());
	}

	@Test
	void T5_PostgreSQL_고카디널리티_후보도_전체_ID를_적재하지_않고_상위10개를_반환한다() {
		List<Long> candidates = new ArrayList<>();
		for (int index = 0; index < 501; index++) {
			candidates.add(insertGame("고카디널리티 후보 " + index, true));
		}
		long highest = candidates.getLast();
		for (int index = 0; index < 3; index++) {
			insertRoom(highest, index);
		}

		var result = candidateQuery.findCandidates(new AssistantGameCandidateQuery.Criteria(List.of("STRATEGY")));

		List<Long> expected = new ArrayList<>();
		expected.add(highest);
		expected.addAll(candidates.subList(0, 9));
		assertEquals(expected, result.stream().map(candidate -> candidate.id()).toList());
	}

	@Test
	void T2_PostgreSQL_카테고리_공개_메커니즘_theme를_모두_AND로_적용하고_RANK_01_순서만_반환한다() {
		long rankOne = insertGame("RANK-01 후보", true);
		long rankTwo = insertGame("RANK-02 후보", true);
		long categoryAndMechanismOnly = insertGame("theme 누락 후보", true);
		long categoryAndThemeOnly = insertGame("mechanism 누락 후보", true);
		long mechanismAndThemeOnly = insertGame("category 누락 후보", false);
		long privateMechanismOnly = insertGame("비공개 mechanism 후보", true);
		linkMechanism(rankOne);
		linkTheme(rankOne);
		linkMechanism(rankTwo);
		linkTheme(rankTwo);
		linkMechanism(categoryAndMechanismOnly);
		linkTheme(categoryAndThemeOnly);
		linkMechanism(mechanismAndThemeOnly);
		linkTheme(mechanismAndThemeOnly);
		linkHiddenMechanism(privateMechanismOnly);
		linkTheme(privateMechanismOnly);
		for (int index = 0; index < 3; index++) {
			insertRoom(rankOne, index);
		}
		insertRoom(rankTwo, 10);
		for (int index = 0; index < 20; index++) {
			insertRoom(categoryAndMechanismOnly, index + 20);
		}

		var result = candidateQuery.findCandidates(new AssistantGameCandidateQuery.Criteria(
			List.of("STRATEGY"), List.of("COOPERATIVE"), List.of("FANTASY"), null, null, null, null));

		assertEquals(List.of(rankOne, rankTwo), result.stream().map(candidate -> candidate.id()).toList());
		assertEquals(List.of(), candidateQuery.findCandidates(new AssistantGameCandidateQuery.Criteria(
			List.of("STRATEGY"), List.of("HIDDEN_COOPERATIVE"), List.of("FANTASY"), null, null, null, null)));
	}

	private long insertGame(String name, boolean strategy) {
		long bggId = 9_000_000L + jdbcTemplate.queryForObject("select count(*) from games", Long.class);
		jdbcTemplate.update(
			"insert into games (bgg_id, name, english_name, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at) values (?, ?, 'Test', '3~4명', '전략', '60분', '설명', '상세 설명', ?, ?)",
			bggId, name, NOW_UTC, NOW_UTC);
		long gameId = jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId);
		if (strategy) {
			jdbcTemplate.update("insert into game_category_relations (game_id, category_id) values (?, ?)", gameId,
				strategyCategoryId);
		}
		return gameId;
	}

	private void linkMechanism(long gameId) {
		jdbcTemplate.update("insert into game_mechanism_relations (game_id, mechanism_id) values (?, ?)", gameId,
			cooperativeMechanismId);
	}

	private void linkTheme(long gameId) {
		jdbcTemplate.update("insert into game_theme_relations (game_id, theme_id) values (?, ?)", gameId,
			fantasyThemeId);
	}

	private void linkHiddenMechanism(long gameId) {
		jdbcTemplate.update("insert into game_mechanism_relations (game_id, mechanism_id) values (?, ?)", gameId,
			hiddenCooperativeMechanismId);
	}

	private void insertRoom(long gameId, int offset) {
		jdbcTemplate.update(
			"insert into rooms (game_id, host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity, active_participant_count, start_at, place, status, version, created_at, updated_at) values (?, ?, 'GAME_FOCUSED', '추천 검증', 'ALL_LEVELS', false, '홍대', 4, 0, ?, '장소', 'RECRUITING', 0, ?, ?)",
			gameId, hostUserId, NOW.plusSeconds(offset).atOffset(ZoneOffset.UTC), NOW_UTC, NOW_UTC);
	}
}
