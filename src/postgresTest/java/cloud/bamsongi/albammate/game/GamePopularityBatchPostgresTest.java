package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest
class GamePopularityBatchPostgresTest extends SharedPostgresIntegrationSupport {

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
	void malformed_CSV_rank_source는_SQL_생성_전에_차단한다() throws Exception {
		assertMalformedRankSourceIsBlocked("boardlife.csv", "unknown,rank\n101,1\n");
		assertMalformedRankSourceIsBlocked("boardlife.csv", "bggId,rank\n101\n");
		assertMalformedRankSourceIsBlocked("boardlife.csv", "bggId,rank\nnot-a-bgg-id,1\n");
		assertMalformedRankSourceIsBlocked("boardlife.csv", "bggId,rank\n101,not-a-rank\n");
		assertMalformedRankSourceIsBlocked("bgg.csv",
			"""
				id,name,yearpublished,rank,bayesaverage,average,usersrated,is_expansion,abstracts_rank,cgs_rank,childrensgames_rank,familygames_rank,partygames_rank,strategygames_rank,thematic_rank,wargames_rank
				101,x,2020,not-a-rank,1,1,1,0,0,0,0,1,0,1,0,0
				""");
	}

	@Test
	void scoreInput_rank_fallback은_manifest가_명시적으로_허용할_때만_사용한다() throws Exception {
		seedGame(101, "fallback 허용 게임");
		seedGame(102, "fallback 차단 게임");
		List<String> scoreInput = List.of(
			"{\"bggId\":101,\"boardlifeRank\":1,\"bggRank\":1}",
			"{\"bggId\":102,\"boardlifeRank\":2,\"bggRank\":2}");

		execute(prepare(List.of(), List.of(), scoreInput, true));
		assertEquals(new BigDecimal("0.700000"), popularityScore(101));
		assertEquals(new BigDecimal("0.000000"), popularityScore(102));

		execute(prepare(List.of(), List.of(), scoreInput, false));
		assertEquals(new BigDecimal("0.000000"), popularityScore(101));
		assertEquals(new BigDecimal("0.000000"), popularityScore(102));

		execute(prepare(List.of(), List.of(), scoreInput, null));
		assertEquals(new BigDecimal("0.000000"), popularityScore(101));
		assertEquals(new BigDecimal("0.000000"), popularityScore(102));
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

	@Test
	void 승인_실행_후_invalid_manifest가_같은_출력_경로의_기존_SQL을_제거하고_차단_보고서를_남긴다() throws Exception {
		Path caseDirectory = Files.createDirectory(tempDirectory.resolve("case-" + System.nanoTime()));
		Path manifest = writeManifest(caseDirectory, List.of("{\"bggId\":101,\"rank\":1}"), List.of(),
			List.of("{\"bggId\":101}"));
		Path output = caseDirectory.resolve("out");

		assertEquals(0, runPrepare(manifest, output).exitCode());
		assertTrue(Files.exists(output.resolve("upsert-game-popularity.sql")));
		assertTrue(Files.readString(output.resolve("quality-report.json"))
			.contains(output.resolve("upsert-game-popularity.sql").toString()));

		Files.writeString(manifest, "{\"schemaVersion\":1,\"status\":\"blocked\"}");
		PreparationResult blocked = runPrepare(manifest, output);

		assertEquals(1, blocked.exitCode(), blocked.outputText());
		assertFalse(Files.exists(output.resolve("upsert-game-popularity.sql")));
		assertTrue(Files.readString(output.resolve("quality-report.json")).contains("\"status\": \"blocked\""));
	}

	@Test
	void manifest_or_out_인자_검증_실패도_기존_SQL을_제거하고_차단_보고서를_남긴다() throws Exception {
		Path caseDirectory = Files.createDirectory(tempDirectory.resolve("case-" + System.nanoTime()));
		Path manifest = writeManifest(caseDirectory, List.of("{\"bggId\":101,\"rank\":1}"), List.of(),
			List.of("{\"bggId\":101}"));
		Path output = caseDirectory.resolve("out");

		assertEquals(0, runPrepare(manifest, output).exitCode());

		PreparationResult missingManifest = runPrepare("--out", output.toString());

		assertEquals(1, missingManifest.exitCode(), missingManifest.outputText());
		assertFalse(Files.exists(output.resolve("upsert-game-popularity.sql")));
		assertTrue(Files.readString(output.resolve("quality-report.json")).contains("\"status\": \"blocked\""));

		PreparationResult missingOutput = runPrepare("--manifest", manifest.toString());

		assertEquals(1, missingOutput.exitCode(), missingOutput.outputText());
		assertTrue(missingOutput.outputText().contains("output path is unavailable for cleanup"));
	}

	@Test
	void 허용된_scoreInput_rank_fallback도_해석_불가_순위를_SQL_생성_전에_차단한다() throws Exception {
		assertInvalidFallbackRankIsBlocked("{\"bggId\":101,\"boardlifeRank\":\"not-a-rank\"}");
		assertInvalidFallbackRankIsBlocked("{\"bggId\":101,\"bggRank\":1.5}");
	}

	@Test
	void 십칠만건_rank와_score_input을_생성한다() throws Exception {
		List<String> ranks = IntStream.rangeClosed(1, 170_000)
			.mapToObj(index -> "{\"bggId\":" + index + ",\"rank\":" + index + "}")
			.toList();
		List<String> scoreInput = IntStream.rangeClosed(1, 170_000)
			.mapToObj(index -> "{\"bggId\":" + index + "}")
			.toList();

		Path sqlPath = prepare(ranks, List.of(), scoreInput);

		assertTrue(Files.exists(sqlPath));
	}

	private Path prepare(List<String> boardlifeRows, List<String> bggRows, List<String> scoreInputRows)
		throws Exception {
		return prepare(boardlifeRows, bggRows, scoreInputRows, null);
	}

	private Path prepare(List<String> boardlifeRows, List<String> bggRows, List<String> scoreInputRows,
		Boolean allowRankFallback)
		throws Exception {
		Path caseDirectory = Files.createDirectory(tempDirectory.resolve("case-" + System.nanoTime()));
		Path manifest = writeManifest(caseDirectory, boardlifeRows, bggRows, scoreInputRows, allowRankFallback);
		Path output = caseDirectory.resolve("out");
		PreparationResult result = runPrepare(manifest, output);
		assertEquals(0, result.exitCode(), result.outputText());
		return output.resolve("upsert-game-popularity.sql");
	}

	private void assertMalformedRankSourceIsBlocked(String sourceFileName, String sourceContent) throws Exception {
		Path caseDirectory = Files.createDirectory(tempDirectory.resolve("case-" + System.nanoTime()));
		Path source = caseDirectory.resolve(sourceFileName);
		Files.writeString(source, sourceContent);
		Path emptyRanks = writeRows(caseDirectory.resolve("empty-ranks.json"), List.of());
		Path scoreInput = writeRows(caseDirectory.resolve("score-input.json"), List.of("{\"bggId\":101}"));
		Path manifest = sourceFileName.equals("boardlife.csv")
			? writeManifest(caseDirectory, source, 1, emptyRanks, 0, scoreInput, 1)
			: writeManifest(caseDirectory, emptyRanks, 0, source, 1, scoreInput, 1);

		PreparationResult result = runPrepare(manifest, caseDirectory.resolve("out"));

		assertEquals(1, result.exitCode(), result.outputText());
	}

	private void assertInvalidFallbackRankIsBlocked(String scoreInputRow) throws Exception {
		Path caseDirectory = Files.createDirectory(tempDirectory.resolve("case-" + System.nanoTime()));
		Path manifest = writeManifest(caseDirectory, List.of(), List.of(), List.of(scoreInputRow), true);
		Path output = caseDirectory.resolve("out");

		PreparationResult result = runPrepare(manifest, output);

		assertEquals(1, result.exitCode(), result.outputText());
		assertFalse(Files.exists(output.resolve("upsert-game-popularity.sql")));
		assertTrue(Files.readString(output.resolve("quality-report.json")).contains("\"status\": \"blocked\""));
	}

	private Path writeManifest(Path caseDirectory, List<String> boardlifeRows, List<String> bggRows,
		List<String> scoreInputRows)
		throws Exception {
		return writeManifest(caseDirectory, boardlifeRows, bggRows, scoreInputRows, null);
	}

	private Path writeManifest(Path caseDirectory, List<String> boardlifeRows, List<String> bggRows,
		List<String> scoreInputRows, Boolean allowRankFallback)
		throws Exception {
		Path boardlife = writeRows(caseDirectory.resolve("boardlife.json"), boardlifeRows);
		Path bgg = writeRows(caseDirectory.resolve("bgg.json"), bggRows);
		Path scoreInput = writeRows(caseDirectory.resolve("score-input.json"), scoreInputRows);
		return writeManifest(caseDirectory, boardlife, boardlifeRows.size(), bgg, bggRows.size(), scoreInput,
			scoreInputRows.size(), allowRankFallback);
	}

	private Path writeManifest(Path caseDirectory, Path boardlife, int boardlifeRows, Path bgg, int bggRows,
		Path scoreInput, int scoreInputRows)
		throws Exception {
		return writeManifest(caseDirectory, boardlife, boardlifeRows, bgg, bggRows, scoreInput, scoreInputRows,
			null);
	}

	private Path writeManifest(Path caseDirectory, Path boardlife, int boardlifeRows, Path bgg, int bggRows,
		Path scoreInput, int scoreInputRows, Boolean allowRankFallback)
		throws Exception {
		Path manifest = caseDirectory.resolve("ranking-manifest.json");
		String allowRankFallbackField = allowRankFallback == null ? ""
			: ",\n\t\t\t    \"allowRankFallback\": " + allowRankFallback;
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
			    "grain": "1 row per bggId", "reviewRequiredRows": 0%s
			  }
			}
			""".formatted(
			jsonPath(boardlife), boardlifeRows, sha256(boardlife),
			jsonPath(bgg), bggRows, sha256(bgg),
			jsonPath(scoreInput), scoreInputRows, sha256(scoreInput), allowRankFallbackField));
		return manifest;
	}

	private PreparationResult runPrepare(Path manifest, Path output) throws Exception {
		return runPrepare("--manifest", manifest.toString(), "--out", output.toString());
	}

	private PreparationResult runPrepare(String... arguments) throws Exception {
		ProcessBuilder processBuilder = new ProcessBuilder(
			"node",
			Path.of(System.getProperty("user.dir"), "scripts/game-ranking/prepare-game-popularity-ranking.mjs")
				.toString());
		processBuilder.command().addAll(List.of(arguments));
		Process process = processBuilder
			.redirectErrorStream(true)
			.start();
		int exitCode = process.waitFor();
		String outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new PreparationResult(exitCode, outputText);
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

	private record PreparationResult(int exitCode, String outputText) {
	}
}
