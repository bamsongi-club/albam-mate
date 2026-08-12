package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@Transactional
class GameNameSearchIndexPostgresTest {

	private static final String GAME_NAME_TRIGRAM_INDEX = "ix_games_name_lower_trgm";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void PostgreSQL_Flyway가_pg_trgm_GIN_인덱스를_생성한다() {
		assertEquals(
			1,
			jdbc.queryForObject("select count(*) from pg_extension where extname = 'pg_trgm'", Integer.class));

		String indexDefinition = jdbc.queryForObject(
			"select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
			String.class,
			GAME_NAME_TRIGRAM_INDEX);

		assertNotNull(indexDefinition);
		assertTrue(indexDefinition.contains("USING gin"));
		assertTrue(indexDefinition.contains("lower"));
		assertTrue(indexDefinition.contains("gin_trgm_ops"));
	}

	@Test
	void 한글자_검색_결과와_세글자_부분일치_GIN_경로를_보존한다() {
		insertGame(1L, "가나다");
		insertGame(2L, "가방");
		insertGame(3L, "나가자");
		for (long number = 4L; number <= 20_003L; number++) {
			insertGame(number, "샘플 이름 " + number);
		}
		for (long number = 20_004L; number <= 20_006L; number++) {
			insertGame(number, "보드게임 후보 " + number);
		}
		jdbc.execute("analyze games");

		assertEquals(
			List.of(1L, 2L, 3L),
			jdbc.queryForList(
				"select id from games where lower(name) like lower(?) order by id",
				Long.class,
				"%가%"));
		assertEquals(
			3L,
			jdbc.queryForObject("select count(*) from games where lower(name) like lower(?)", Long.class, "%가%"));

		assertEquals(
			List.of(20_004L, 20_005L, 20_006L),
			jdbc.queryForList(
				"select id from games where lower(name) like lower(?) order by id",
				Long.class,
				"%보드게%"));
		assertEquals(
			3L,
			jdbc.queryForObject(
				"select count(*) from games where lower(name) like lower(?)", Long.class, "%보드게%"));

		String plan = String.join(
			"\n",
			jdbc.queryForList(
				"explain (format text) select id from games where lower(name) like lower(?)",
				String.class,
				"%보드게%"));

		assertNotNull(plan);
		assertTrue(plan.contains(GAME_NAME_TRIGRAM_INDEX));
	}

	@Test
	void ERD와_운영문서가_하이브리드_경계와_재측정_조건을_기록한다() throws Exception {
		String erd = Files.readString(Path.of("docs/ERD.md"));
		String guide = Files.readString(Path.of("docs/guides/P1_SEARCH_PERFORMANCE.md"));

		assertTrue(erd.contains(GAME_NAME_TRIGRAM_INDEX));
		assertTrue(guide.contains("1·2글자"));
		assertTrue(guide.contains("3글자 이상"));
		assertTrue(guide.contains("재측정"));
	}

	private void insertGame(long id, String name) {
		jdbc.update(
			"""
				insert into games(
					id, bgg_id, name, english_name, supported_player_count, tag, estimated_play_time,
					description, detail_description, created_at, updated_at
				) values (?, ?, ?, ?, '2~4명', '전략', '30분', '설명', '상세 설명', current_timestamp, current_timestamp)
				""",
			id,
			1_000_000L + id,
			name,
			"Game " + id);
	}
}
