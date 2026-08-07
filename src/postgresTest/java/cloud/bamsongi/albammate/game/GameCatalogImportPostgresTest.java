package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class GameCatalogImportPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("game_catalog_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@TempDir
	Path tempDirectory;

	@AfterEach
	void 데이터베이스를_정리한다() {
		jdbcTemplate.execute("alter table games drop constraint if exists ck_test_game_name");
		jdbcTemplate.execute("delete from games");
		jdbcTemplate.execute("delete from game_mechanisms");
	}

	@Test
	void 재적재는_내부_ID를_유지하고_새_입력에_없는_게임을_삭제하지_않는다() throws Exception {
		executeSql(
			prepareSql(
				List.of(
					game(1, 10, "첫 번째 게임", "First Game"),
					game(2, 20, "두 번째 게임", "Second Game"))));

		Long firstId = gameId(10);
		Long secondId = gameId(20);

		executeSql(prepareSql(List.of(game(1, 10, "수정된 첫 번째 게임", "First Game"))));

		assertEquals(firstId, gameId(10));
		assertEquals("수정된 첫 번째 게임", gameName(10));
		assertEquals(secondId, gameId(20));
		assertEquals(2, gameCount());
	}

	@Test
	void 출시_연도_재적재는_내부_ID를_유지하고_미상_연도는_NULL로_저장한다() throws Exception {
		GameInput knownYear = game(1, 10, "출시 연도 있는 게임", "Known Release Year Game");
		knownYear.yearpublished = "1995";
		GameInput unknownYear = game(2, 20, "출시 연도 미상 게임", "Unknown Release Year Game");
		unknownYear.yearpublished = "";
		executeSql(prepareSql(List.of(knownYear, unknownYear)));

		Long knownGameId = gameId(10);
		executeSql(prepareSql(List.of(knownYear, unknownYear)));

		assertEquals(knownGameId, gameId(10));
		assertEquals(1995, jdbcTemplate.queryForObject(
			"select release_year from games where bgg_id = 10", Integer.class));
		assertNull(jdbcTemplate.queryForObject(
			"select release_year from games where bgg_id = 20", Integer.class));
	}

	@Test
	void 적재_중_한_행이_실패하면_배치_전체를_롤백한다() throws Exception {
		jdbcTemplate.execute(
			"alter table games add constraint ck_test_game_name check (name <> '실패 게임')");
		String sql = prepareSql(
			List.of(
				game(1, 10, "정상 게임", "Valid Game"),
				game(2, 20, "실패 게임", "Invalid Game")));

		assertThrows(SQLException.class, () -> executeSql(sql));

		assertEquals(0, gameCount());
	}

	@Test
	void 검색_수치를_적재해도_표시_문자열을_유지한다() throws Exception {
		executeSql(prepareSql(List.of(game(1, 10, "표시 유지 게임", "Display Game"))));

		assertEquals("2~4명", jdbcTemplate.queryForObject(
			"select supported_player_count from games where bgg_id = 10", String.class));
		assertEquals("60~120분", jdbcTemplate.queryForObject(
			"select estimated_play_time from games where bgg_id = 10", String.class));
		assertEquals(2, jdbcTemplate.queryForObject(
			"select min_players from games where bgg_id = 10", Integer.class));
		assertEquals(4, jdbcTemplate.queryForObject(
			"select max_players from games where bgg_id = 10", Integer.class));
	}

	@Test
	void 검색_수치_제약은_불완전하거나_유효하지_않은_범위를_거절한다() throws Exception {
		executeSql(prepareSql(List.of(game(1, 10, "제약 게임", "Constraint Game"))));

		assertConstraintViolation("update games set min_players = 2, max_players = null where bgg_id = 10");
		assertConstraintViolation("update games set min_players = 0, max_players = 4 where bgg_id = 10");
		assertConstraintViolation("update games set min_players = 5, max_players = 4 where bgg_id = 10");
		assertConstraintViolation(
			"update games set min_play_time_minutes = 10, max_play_time_minutes = null where bgg_id = 10");
		assertConstraintViolation(
			"update games set min_play_time_minutes = 0, max_play_time_minutes = 20 where bgg_id = 10");
		assertConstraintViolation(
			"update games set min_play_time_minutes = 30, max_play_time_minutes = 20 where bgg_id = 10");
		assertConstraintViolation("update games set complexity = 0.00 where bgg_id = 10");
	}

	@Test
	void V8은_기존_범위_밖_복잡도를_NULL로_정규화한_뒤_제약을_추가한다() throws Exception {
		String schema = "v8_legacy_complexity_" + System.nanoTime();
		Path migration = Path.of(System.getProperty("user.dir"))
			.resolve("src/main/resources/db/migration/V8__add_p1_game_search_numeric_fields.sql");

		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			connection.setAutoCommit(false);
			statement.execute("create schema " + schema);
			statement.execute("set local search_path to " + schema);
			statement.execute("create table games (id integer primary key, complexity decimal(3, 2))");
			statement.execute(
				"insert into games (id, complexity) values (1, 0.00), (2, 0.50), (3, 1.00), (4, 5.50)");
			for (String sql : Files.readString(migration, StandardCharsets.UTF_8).split(";")) {
				if (!sql.isBlank()) {
					statement.execute(sql);
				}
			}
			try (var result = statement.executeQuery("select id, complexity from games order by id")) {
				result.next();
				assertNull(result.getBigDecimal("complexity"));
				result.next();
				assertNull(result.getBigDecimal("complexity"));
				result.next();
				assertEquals(new java.math.BigDecimal("1.00"), result.getBigDecimal("complexity"));
				result.next();
				assertNull(result.getBigDecimal("complexity"));
			}
			statement.execute("savepoint before_below_range_update");
			assertThrows(SQLException.class,
				() -> statement.execute("update games set complexity = 0.50 where id = 3"));
			statement.execute("rollback to savepoint before_below_range_update");
			assertThrows(SQLException.class,
				() -> statement.execute("update games set complexity = 5.50 where id = 3"));
		} finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
		}
	}

	@Test
	void 메커니즘_관계의_게임을_해석하지_못하면_적재_전체를_롤백한다() throws Exception {
		executeSql(prepareSql(List.of(game(1, 10, "기존 게임", "Existing Game"))));

		GameInput missingGame = game(1, 20, "없는 게임", "Missing Game");
		missingGame.mechanisms = List.of(mechanism(2040, "Hand Management", "핸드 관리"));

		assertThrows(SQLException.class, () -> executeSql(prepareMechanismSql(List.of(missingGame))));

		assertEquals(0, mechanismCount());
		assertEquals(0, mechanismRelationCount());
	}

	@Test
	void 공개_메커니즘은_검수된_비공백_설명만_저장한다() throws Exception {
		GameInput input = game(1, 10, "공개 설명 메커니즘 게임", "Public Description Mechanism Game");
		input.mechanisms = List.of(mechanism(2040, "Hand Management", "핸드 관리"));
		executeSql(prepareSql(List.of(input)));
		executeSql(prepareMechanismSql(List.of(input)));

		assertEquals("검수된 한글 설명입니다.", jdbcTemplate.queryForObject(
			"select description_ko from game_mechanisms where bgg_mechanism_id = 2040", String.class));
		assertConstraintViolation(
			"update game_mechanisms set description_ko = ' ' where bgg_mechanism_id = 2040");
	}

	@Test
	void 메커니즘_재적재는_관계를_승인_스냅샷에_수렴시키고_누락된_공개_항목을_비공개로_전환한다() throws Exception {
		GameInput first = game(1, 10, "첫 번째 게임", "First Game");
		first.mechanisms = List.of(
			mechanism(2040, "Hand Management", "핸드 관리"),
			mechanism(2072, "Dice Rolling", "주사위 굴림"));
		GameInput second = game(2, 20, "두 번째 게임", "Second Game");
		second.mechanisms = List.of(mechanism(2072, "Dice Rolling", "주사위 굴림"));
		executeSql(prepareSql(List.of(first, second)));
		executeSql(prepareMechanismSql(List.of(first, second)));

		Long handManagementId = mechanismId(2040);
		Long diceRollingId = mechanismId(2072);
		assertEquals(3, mechanismRelationCount());

		GameInput approved = game(1, 10, "첫 번째 게임", "First Game");
		approved.mechanisms = List.of(mechanism(2040, "Hand Management", "핸드 관리"));
		executeSql(prepareMechanismSql(List.of(approved)));

		assertEquals(handManagementId, mechanismId(2040));
		assertEquals(diceRollingId, mechanismId(2072));
		assertEquals(1, mechanismRelationCount());
		assertEquals(true, jdbcTemplate.queryForObject(
			"select is_public from game_mechanisms where bgg_mechanism_id = 2040", Boolean.class));
		assertEquals(false, jdbcTemplate.queryForObject(
			"select is_public from game_mechanisms where bgg_mechanism_id = 2072", Boolean.class));
		assertNull(jdbcTemplate.queryForObject(
			"select featured_order from game_mechanisms where bgg_mechanism_id = 2072", Integer.class));
		assertConstraintViolation(
			"update game_mechanisms set description_ko = '' where bgg_mechanism_id = 2040");
		jdbcTemplate.update("update game_mechanisms set description_ko = null where bgg_mechanism_id = 2072");
		assertNull(jdbcTemplate.queryForObject(
			"select description_ko from game_mechanisms where bgg_mechanism_id = 2072", String.class));

		executeSql(prepareMechanismSql(List.of(approved)));
		assertEquals(1, mechanismRelationCount());
	}

	@Test
	void 설명이_없는_메커니즘_배치는_운영_SQL을_만들지_않는다() throws Exception {
		GameInput input = game(1, 10, "설명 없는 메커니즘 게임", "Missing Description Mechanism Game");
		input.mechanisms = List.of(mechanism(2040, "Hand Management", "핸드 관리", " "));

		CatalogPreparation preparation = prepareMechanismCatalog(List.of(input));

		assertEquals(1, preparation.exitCode(), preparation.output());
		assertFalse(Files.exists(preparation.outputPath().resolve("service-catalog.json")));
		assertFalse(Files.exists(preparation.outputPath().resolve("upsert-games.sql")));
		assertFalse(Files.exists(preparation.outputPath().resolve("service-mechanism-catalog.json")));
		assertFalse(Files.exists(preparation.outputPath().resolve("upsert-game-mechanisms.sql")));
	}

	private void assertConstraintViolation(String sql) {
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.execute(sql));
	}

	private String prepareSql(List<GameInput> games) throws Exception {
		Path caseDirectory = Files.createTempDirectory(tempDirectory, "catalog-");
		Path gamesPath = caseDirectory.resolve("games.json");
		Path ranksPath = caseDirectory.resolve("ranks.csv");
		Path manifestPath = caseDirectory.resolve("manifest.json");
		Path outputPath = caseDirectory.resolve("output");
		Files.writeString(gamesPath, gamesJson(games), StandardCharsets.UTF_8);
		Files.writeString(ranksPath, ranksCsv(games), StandardCharsets.UTF_8);
		Files.writeString(
			manifestPath,
			manifestJson(gamesPath, ranksPath, games.size()),
			StandardCharsets.UTF_8);

		Path script = Path.of(System.getProperty("user.dir"))
			.resolve("scripts/game-catalog/prepare-game-catalog.mjs");
		Process process = new ProcessBuilder(
			"node",
			script.toString(),
			"--games",
			gamesPath.toString(),
			"--ranks",
			ranksPath.toString(),
			"--manifest",
			manifestPath.toString(),
			"--out",
			outputPath.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exitCode = process.waitFor();
		assertEquals(0, exitCode, output);
		return Files.readString(outputPath.resolve("upsert-games.sql"), StandardCharsets.UTF_8);
	}

	private String prepareMechanismSql(List<GameInput> games) throws Exception {
		CatalogPreparation preparation = prepareMechanismCatalog(games);
		assertEquals(0, preparation.exitCode(), preparation.output());
		return Files.readString(preparation.outputPath().resolve("upsert-game-mechanisms.sql"), StandardCharsets.UTF_8);
	}

	private CatalogPreparation prepareMechanismCatalog(List<GameInput> games) throws Exception {
		Path caseDirectory = Files.createTempDirectory(tempDirectory, "mechanism-catalog-");
		Path gamesPath = caseDirectory.resolve("games.json");
		Path ranksPath = caseDirectory.resolve("ranks.csv");
		Path manifestPath = caseDirectory.resolve("manifest.json");
		Path outputPath = caseDirectory.resolve("output");
		Files.writeString(gamesPath, gamesJson(games), StandardCharsets.UTF_8);
		Files.writeString(ranksPath, ranksCsv(games), StandardCharsets.UTF_8);
		Files.writeString(
			manifestPath,
			mechanismManifestJson(gamesPath, ranksPath, games),
			StandardCharsets.UTF_8);

		Path script = Path.of(System.getProperty("user.dir"))
			.resolve("scripts/game-catalog/prepare-game-catalog.mjs");
		Process process = new ProcessBuilder(
			"node",
			script.toString(),
			"--games",
			gamesPath.toString(),
			"--ranks",
			ranksPath.toString(),
			"--manifest",
			manifestPath.toString(),
			"--out",
			outputPath.toString())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int exitCode = process.waitFor();
		return new CatalogPreparation(outputPath, exitCode, output);
	}

	private void executeSql(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			try {
				statement.execute(sql);
			} catch (SQLException exception) {
				try (Statement rollbackStatement = connection.createStatement()) {
					rollbackStatement.execute("ROLLBACK");
				} catch (SQLException rollbackException) {
					exception.addSuppressed(rollbackException);
				}
				throw exception;
			}
		}
	}

	private Long gameId(long bggId) {
		return jdbcTemplate.queryForObject(
			"select id from games where bgg_id = ?", Long.class, bggId);
	}

	private String gameName(long bggId) {
		return jdbcTemplate.queryForObject(
			"select name from games where bgg_id = ?", String.class, bggId);
	}

	private int gameCount() {
		return jdbcTemplate.queryForObject("select count(*) from games", Integer.class);
	}

	private int mechanismCount() {
		return jdbcTemplate.queryForObject("select count(*) from game_mechanisms", Integer.class);
	}

	private Long mechanismId(long bggMechanismId) {
		return jdbcTemplate.queryForObject(
			"select id from game_mechanisms where bgg_mechanism_id = ?", Long.class, bggMechanismId);
	}

	private int mechanismRelationCount() {
		return jdbcTemplate.queryForObject("select count(*) from game_mechanism_relations", Integer.class);
	}

	private String gamesJson(List<GameInput> games) {
		return games.stream()
			.map(
				game -> """
					{
					  "id": %d,
					  "bgg_id": "%d",
					  "name": "%s",
					  "english_name": "%s",
					  "alias": "%s, %s",
					  "image_url": "https://example.com/%d.jpg",
					  "supported_player_count": "2~4명",
					  "tag": "전략",
					  "estimated_play_time": "60~120분",
					  "complexity": 3.25,
					  "description": "%s 설명",
					  "detail_description": "%s 상세 설명"%s
					}
					"""
					.formatted(
						game.rank,
						game.bggId,
						game.name,
						game.englishName,
						game.name,
						game.englishName,
						game.bggId,
						game.name,
						game.name,
						mechanismsJson(game.mechanisms))
					.strip())
			.collect(java.util.stream.Collectors.joining(",\n", "[\n", "\n]\n"));
	}

	private String ranksCsv(List<GameInput> games) {
		String header = "id,name,yearpublished,rank,bayesaverage,average,usersrated,is_expansion,"
			+ "abstracts_rank,cgs_rank,childrensgames_rank,familygames_rank,"
			+ "partygames_rank,strategygames_rank,thematic_rank,wargames_rank\n";
		String body = games.stream()
			.map(
				game -> "%d,\"%s\",%s,%d,8.0,8.0,100,0,,,,,,1,,"
					.formatted(game.bggId, game.englishName, game.yearpublished, game.rank))
			.collect(java.util.stream.Collectors.joining("\n"));
		return header + body + "\n";
	}

	private String manifestJson(Path gamesPath, Path ranksPath, int rowCount) throws IOException {
		return """
			{
			  "schemaVersion": 1,
			  "batchId": "postgres-integration-test",
			  "toolCommit": "0123456789abcdef0123456789abcdef01234567",
			  "sources": {
			    "games": {
			      "fileName": "games.json",
			      "sha256": "%s",
			      "sourceReference": "통합 테스트 fixture",
			      "acquiredAt": "2026-07-24T00:00:00Z",
			      "usageTerms": "테스트 전용"
			    },
			    "ranks": {
			      "fileName": "ranks.csv",
			      "sha256": "%s",
			      "sourceReference": "통합 테스트 fixture",
			      "acquiredAt": "2026-07-24T00:00:00Z",
			      "usageTerms": "테스트 전용"
			    }
			  },
			  "fieldSources": {
			    "bgg_id": "ranks.id",
			    "name": "games.name",
			    "english_name": "games.english_name",
			    "alias": "games.alias",
			    "image_url": "games.image_url",
			    "supported_player_count": "games.supported_player_count",
			    "tag": "games.tag",
			    "estimated_play_time": "games.estimated_play_time",
			    "min_players": "games.supported_player_count를 검증해 정규화",
			    "max_players": "games.supported_player_count를 검증해 정규화",
			    "min_play_time_minutes": "games.estimated_play_time를 검증해 정규화",
			    "max_play_time_minutes": "games.estimated_play_time를 검증해 정규화",
			    "complexity": "games.complexity",
			    "release_year": "ranks.yearpublished",
			    "description": "games.description",
			    "detail_description": "games.detail_description"
			  },
			  "selectionRules": {
			    "include": "BGG 기준 스냅샷과 bgg_id가 일치하고 필수 검수를 통과한 후보만 포함",
			    "exclude": "매핑·필수값·판본 근거가 부족한 후보는 식별자와 사유를 남기고 제외"
			  },
			  "versionRules": {
			    "baseGame": "BGG 본판으로 확인된 항목만 본판으로 분류",
			    "expansion": "BGG 확장은 본판과 구분하고 서비스 목록 반영 여부를 검수",
			    "variant": "변형 여부를 확인할 수 없으면 임의로 병합하지 않고 제외"
			  },
			  "selection": {
			    "candidateRows": %d,
			    "includedRows": %d,
			    "excludedRows": 0,
			    "exclusions": []
			  },
			  "review": {
			    "status": "approved",
			    "reviewedAt": "2026-07-27T10:00:00Z",
			    "reviewers": ["postgres-test"],
			    "acceptedWarnings": []
			  }
			}
			"""
			.formatted(sha256(gamesPath), sha256(ranksPath), rowCount, rowCount);
	}

	private String mechanismManifestJson(Path gamesPath, Path ranksPath, List<GameInput> games) throws IOException {
		int relationCount = games.stream().mapToInt(game -> game.mechanisms.size()).sum();
		String approvedCodes = games.stream()
			.flatMap(game -> game.mechanisms.stream())
			.distinct()
			.map(mechanism -> "\"%d\":\"%s\"".formatted(
				mechanism.bggMechanismId, mechanism.code()))
			.collect(java.util.stream.Collectors.joining(","));
		String checksum = sha256("{" + approvedCodes + "}");
		String baseManifest = manifestJson(gamesPath, ranksPath, games.size());
		return baseManifest.substring(0, baseManifest.lastIndexOf('}')) + """
			  ,"mechanismCatalog": {
			    "publishedCount": %d,
			    "relationCount": %d,
			    "sourceReference": "postgres mechanism fixture",
			    "reviewedBy": "postgres-test",
			    "reviewedAt": "2026-08-04T00:00:00Z",
			    "approvedCodes": {%s},
			    "approvedCodesSha256": "%s"
			  }
			}
			""".formatted(
			(int)games.stream().flatMap(game -> game.mechanisms.stream()).distinct().count(),
			relationCount,
			approvedCodes,
			checksum);
	}

	private String sha256(Path path) throws IOException {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private GameInput game(int rank, long bggId, String name, String englishName) {
		return new GameInput(rank, bggId, name, englishName);
	}

	private MechanismInput mechanism(long bggMechanismId, String nameEn, String nameKo) {
		return mechanism(bggMechanismId, nameEn, nameKo, "검수된 한글 설명입니다.");
	}

	private MechanismInput mechanism(long bggMechanismId, String nameEn, String nameKo, String descriptionKo) {
		return new MechanismInput(bggMechanismId, nameEn, nameKo, descriptionKo);
	}

	private String mechanismsJson(List<MechanismInput> mechanisms) {
		if (mechanisms.isEmpty()) {
			return "";
		}
		return ",\n  \"mechanisms\": [" + mechanisms.stream()
			.map(mechanism -> "{\"bgg_id\":\"%d\",\"name\":\"%s\",\"name_ko\":\"%s\",\"description_ko\":\"%s\"}"
				.formatted(mechanism.bggMechanismId, mechanism.nameEn, mechanism.nameKo, mechanism.descriptionKo))
			.collect(java.util.stream.Collectors.joining(",")) + "]";
	}

	private static final class GameInput {
		private final int rank;
		private final long bggId;
		private final String name;
		private final String englishName;
		private String yearpublished = "2020";
		private List<MechanismInput> mechanisms = List.of();

		private GameInput(int rank, long bggId, String name, String englishName) {
			this.rank = rank;
			this.bggId = bggId;
			this.name = name;
			this.englishName = englishName;
		}
	}

	private record MechanismInput(long bggMechanismId, String nameEn, String nameKo, String descriptionKo) {
		private String code() {
			return switch ((int)bggMechanismId) {
				case 2040 -> "HAND_MANAGEMENT";
				case 2072 -> "DICE_ROLLING";
				default -> throw new IllegalArgumentException("Unsupported test mechanism: " + bggMechanismId);
			};
		}
	}

	private record CatalogPreparation(Path outputPath, int exitCode, String output) {
	}
}
