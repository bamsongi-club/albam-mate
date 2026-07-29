package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
					  "detail_description": "%s 상세 설명"
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
						game.name)
					.strip())
			.collect(java.util.stream.Collectors.joining(",\n", "[\n", "\n]\n"));
	}

	private String ranksCsv(List<GameInput> games) {
		String header = "id,name,yearpublished,rank,bayesaverage,average,usersrated,is_expansion,"
			+ "abstracts_rank,cgs_rank,childrensgames_rank,familygames_rank,"
			+ "partygames_rank,strategygames_rank,thematic_rank,wargames_rank\n";
		String body = games.stream()
			.map(
				game -> "%d,\"%s\",2020,%d,8.0,8.0,100,0,,,,,,1,,"
					.formatted(game.bggId, game.englishName, game.rank))
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
			    "complexity": "games.complexity",
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

	private String sha256(Path path) throws IOException {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private GameInput game(int rank, long bggId, String name, String englishName) {
		return new GameInput(rank, bggId, name, englishName);
	}

	private record GameInput(int rank, long bggId, String name, String englishName) {
	}
}
