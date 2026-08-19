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

	private static final String POSTGRES_IMAGE = "postgres:18.4";
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
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table rooms, game_category_relations, game_categories, games, users restart identity cascade");
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

	private void insertRoom(long gameId, int offset) {
		jdbcTemplate.update(
			"insert into rooms (game_id, host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity, active_participant_count, start_at, place, status, version, created_at, updated_at) values (?, ?, 'GAME_FOCUSED', '추천 검증', 'ALL_LEVELS', false, '홍대', 4, 0, ?, '장소', 'RECRUITING', 0, ?, ?)",
			gameId, hostUserId, NOW.plusSeconds(offset).atOffset(ZoneOffset.UTC), NOW_UTC, NOW_UTC);
	}
}
