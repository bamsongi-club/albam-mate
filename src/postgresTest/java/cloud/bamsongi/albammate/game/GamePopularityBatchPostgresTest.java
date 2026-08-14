package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class GamePopularityBatchPostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("game_popularity_batch_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@TempDir
	Path tempDirectory;

	@AfterEach
	void 게임_인기_배치_테스트_데이터를_정리한다() {
		jdbcTemplate.execute("delete from rooms");
		jdbcTemplate.execute("delete from games");
		jdbcTemplate.execute("delete from users");
	}

	@Test
	void 순위_1위_최하위_결측과_단일_양의_순위를_0에서_1로_정규화한다() throws Exception {
		seedGame(101, "첫 번째 게임");
		seedGame(102, "두 번째 게임");
		seedGame(103, "세 번째 게임");

		execute(
			prepare(
				List.of("{\"bggId\":101,\"rank\":1}", "{\"bggId\":102,\"rank\":3}",
					"{\"bggId\":103,\"rank\":0}"),
				List.of(),
				List.of("{\"bggId\":101}", "{\"bggId\":102}", "{\"bggId\":103}")));

		assertEquals(new BigDecimal("0.600000"), popularityScore(101));
		assertEquals(new BigDecimal("0.000000"), popularityScore(102));
		assertEquals(new BigDecimal("0.000000"), popularityScore(103));
		assertTrue(allScoresAreWithinNormalizedRange());

		execute(prepare(List.of("{\"bggId\":103,\"rank\":5}"), List.of(), List.of("{\"bggId\":103}")));

		assertEquals(new BigDecimal("0.600000"), popularityScore(103));
	}

	@Test
	void BoardLife_Albam_BGG_가중치를_6대3대1로_적용해_종합점수를_계산한다() throws Exception {
		long first = seedGame(101, "줄루");
		long second = seedGame(102, "알파");
		seedGame(103, "브라보");
		insertRoom(first, "GAME_FOCUSED", "RECRUITING");
		insertRoom(first, "GAME_FOCUSED", "CLOSED");
		insertRoom(first, "GAME_FOCUSED", "FINISHED");
		insertRoom(second, "GAME_FOCUSED", "RECRUITING");

		execute(
			prepare(
				List.of("{\"bggId\":101,\"rank\":1}", "{\"bggId\":102,\"rank\":2}"),
				List.of("{\"bggId\":101,\"rank\":2}", "{\"bggId\":102,\"rank\":1}"),
				List.of("{\"bggId\":101}", "{\"bggId\":102}")));

		assertEquals(new BigDecimal("0.900000"), popularityScore(101));
		assertEquals(new BigDecimal("0.100000"), popularityScore(102));
		assertEquals(new BigDecimal("0.000000"), popularityScore(103));
		assertEquals(List.of("줄루", "알파", "브라보"), namesByPopularity());
	}

	@Test
	void 중복_BGG_ID는_가장_작은_순위를_대표로_쓰고_미매칭_확장판_입력은_반영하지_않는다() throws Exception {
		seedGame(101, "매핑된 게임");
		seedGame(102, "순위 없는 게임");

		execute(
			prepare(
				List.of("{\"bggId\":101,\"rank\":2}", "{\"bggId\":101,\"rank\":1}",
					"{\"bggId\":999,\"rank\":3}"),
				List.of(),
				List.of("{\"bggId\":101}", "{\"bggId\":999}")));

		assertEquals(new BigDecimal("0.600000"), popularityScore(101));
		assertEquals(new BigDecimal("0.000000"), popularityScore(102));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from games where bgg_id = 999", Integer.class));
	}

	@Test
	void 내부_집계는_GAME_FOCUSED만_대상으로하고_CANCELED는_제외한다() throws Exception {
		long first = seedGame(101, "ID가 빠른 게임");
		long second = seedGame(102, "ID가 늦은 게임");
		long ignored = seedGame(103, "제외 게임");
		insertRoom(first, "GAME_FOCUSED", "RECRUITING");
		insertRoom(first, "GAME_FOCUSED", "FINISHED");
		insertRoom(first, "GAME_FOCUSED", "CANCELED");
		insertRoom(second, "GAME_FOCUSED", "RECRUITING");
		insertRoom(second, "GAME_FOCUSED", "CLOSED");
		insertRoom(ignored, "PERSON_FOCUSED", "RECRUITING");
		insertRoom(ignored, "GAME_FOCUSED", "CANCELED");

		execute(prepare(List.of(), List.of(), List.of()));

		assertEquals(new BigDecimal("0.300000"), popularityScore(101));
		assertEquals(new BigDecimal("0.000000"), popularityScore(102));
		assertEquals(new BigDecimal("0.000000"), popularityScore(103));
	}

	private Path prepare(List<String> boardlifeRows, List<String> bggRows, List<String> scoreInputRows)
		throws Exception {
		Path caseDirectory = Files.createDirectory(tempDirectory.resolve("case-" + System.nanoTime()));
		Path boardlife = writeRows(caseDirectory.resolve("boardlife.json"), boardlifeRows);
		Path bgg = writeRows(caseDirectory.resolve("bgg.json"), bggRows);
		Path scoreInput = writeRows(caseDirectory.resolve("score-input.json"), scoreInputRows);
		Path manifest = caseDirectory.resolve("ranking-manifest.json");
		Files.writeString(manifest, """
			{
			  "schemaVersion": 1,
			  "status": "approved",
			  "batchId": "rank-test",
			  "sources": {
			    "boardlife": {"path": "%s", "rows": %d, "sha256": "%s"},
			    "bgg": {"path": "%s", "rows": %d, "sha256": "%s"}
			  },
			  "scoreInput": {
			    "path": "%s", "rows": %d, "sha256": "%s",
			    "grain": "1 row per bggId", "reviewRequiredRows": 0
			  }
			}
			""".formatted(
			jsonPath(boardlife), boardlifeRows.size(), sha256(boardlife),
			jsonPath(bgg), bggRows.size(), sha256(bgg),
			jsonPath(scoreInput), scoreInputRows.size(), sha256(scoreInput)));
		Path output = caseDirectory.resolve("out");
		Process process = new ProcessBuilder(
			"node",
			Path.of(System.getProperty("user.dir"), "scripts/game-ranking/prepare-game-popularity-ranking.mjs")
				.toString(),
			"--manifest", manifest.toString(), "--out", output.toString())
			.redirectErrorStream(true)
			.start();
		int exitCode = process.waitFor();
		String outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, exitCode, outputText);
		return output.resolve("upsert-game-popularity.sql");
	}

	private Path writeRows(Path path, List<String> rows) throws IOException {
		Files.writeString(path, "[\n" + String.join(",\n", rows) + "\n]\n");
		return path;
	}

	private void execute(Path sqlPath) throws Exception {
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
			for (String statementText : Files.readString(sqlPath).split(";")) {
				if (!statementText.isBlank()) {
					statement.execute(statementText);
				}
			}
		}
	}

	private long seedGame(long bggId, String name) {
		jdbcTemplate.update(
			"""
				insert into games
				    (bgg_id, name, english_name, supported_player_count, tag, estimated_play_time,
				     description, detail_description, created_at, updated_at)
				values (?, ?, ?, '2~4명', '테스트', '60분', '설명', '상세 설명', current_timestamp, current_timestamp)
				""",
			bggId, name, name + " English");
		return jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId);
	}

	private void insertRoom(long gameId, String roomType, String status) {
		Long hostUserId = jdbcTemplate.queryForObject(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				values (?, 'test-hash', ?, current_timestamp, current_timestamp)
				returning id
				""",
			Long.class, "popularity-" + System.nanoTime() + "@example.com", "테스트 호스트");
		jdbcTemplate.update(
			"""
				insert into rooms
				    (game_id, host_user_id, room_type, title, experience_level, is_rulemaster_led,
				     capacity, active_participant_count, start_at, place, status, created_at, updated_at)
				values (?, ?, ?, '인기 점수 집계 방', 'ALL_LEVELS', false, 2, 0,
				        current_timestamp, '테스트 장소', ?, current_timestamp, current_timestamp)
				""",
			gameId, hostUserId, roomType, status);
	}

	private BigDecimal popularityScore(long bggId) {
		return jdbcTemplate.queryForObject(
			"select popularity_score from games where bgg_id = ?", BigDecimal.class, bggId);
	}

	private boolean allScoresAreWithinNormalizedRange() {
		return jdbcTemplate.queryForObject(
			"select bool_and(popularity_score between 0 and 1) from games", Boolean.class);
	}

	private List<String> namesByPopularity() {
		return jdbcTemplate.queryForList(
			"select name from games order by popularity_score desc, name asc, id asc", String.class);
	}

	private String sha256(Path path) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
	}

	private String jsonPath(Path path) {
		return path.toString().replace("\\", "\\\\");
	}
}
