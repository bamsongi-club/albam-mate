package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@SpringBootTest
@AutoConfigureMockMvc
class GameNameCorrectionPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MockMvc mockMvc;

	@TempDir
	private Path temp;

	@AfterEach
	void 데이터베이스를_정리한다() {
		jdbc.execute("truncate table games, game_categories restart identity cascade");
	}

	@Test
	void T1_자동_음차_후보는_적재하지_않고_검수된_한글명은_유지한다() throws Exception {
		Path correctedSql = correct(
			List.of(
				game(101, "오토매틱 캔디데이트", "Automatic Candidate"),
				game(102, "검수된 한글명", "Reviewed Candidate")),
				"101,Automatic Candidate,오토매틱 캔디데이트,추정번역(자동음차),N\n"
					+ "102,Reviewed Candidate,검수된 한글명,국내 정발명,Y\n",
				"<item id=\"101\"><name type=\"primary\" value=\"Automatic Candidate\"/></item>"
					+ "<item id=\"102\"><name type=\"primary\" value=\"Reviewed Candidate\"/></item>");

		execute(correctedSql);

		assertEquals("Automatic Candidate", nameOf(101));
		assertEquals("검수된 한글명", nameOf(102));
	}

	@Test
	void T2_370749는_웬디_어른이_되렴으로_적재되고_API에_노출된다() throws Exception {
		Path correctedSql = correct(
			List.of(game(370749, "우엔드이, 그르오 우프", "Wendy, Grow Up")),
			"370749,\"Wendy, Grow Up\",\"우엔드이, 그르오 우프\",추정번역(자동음차),N\n",
			"<item id=\"370749\"><name type=\"primary\" value=\"Wendy, Grow Up\"/>"
				+ "<name type=\"alternate\" value=\"웬디, 어른이 되렴\"/></item>");

		execute(correctedSql);

		assertEquals("웬디, 어른이 되렴", nameOf(370749));
		mockMvc.perform(get("/api/games").param("keyword", "웬디, 어른이 되렴"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content.length()").value(1))
			.andExpect(jsonPath("$.data.content[0].bggId").value(370749))
			.andExpect(jsonPath("$.data.content[0].name").value("웬디, 어른이 되렴"));
	}

	@Test
	void T3_한글명_후보가_없으면_BGG_primary_영문명으로_fallback한다() throws Exception {
		Path correctedSql = correct(
			List.of(game(103, "추정 음차", "No Korean Alternate")),
			"103,No Korean Alternate,추정 음차,추정번역(자동음차),N\n",
			"<item id=\"103\"><name type=\"primary\" value=\"No Korean Alternate\"/></item>");

		execute(correctedSql);

		assertEquals("No Korean Alternate", nameOf(103));
	}

	@Test
	void T4_행수_관계_내부ID와_checksum_provenance를_보존한다() throws Exception {
		Path correctedSql = correct(
			List.of(
				game(201, "자동 하나", "Primary One"),
				game(202, "검수 둘", "Primary Two"),
				game(203, "자동 셋", "Primary Three")),
			"201,Primary One,자동 하나,추정번역(자동음차),N\n"
				+ "202,Primary Two,검수된 둘,국내 정발명,Y\n"
				+ "203,Primary Three,자동 셋,추정번역(자동음차),N\n",
			"<item id=\"201\"><name type=\"primary\" value=\"Primary One\"/></item>"
				+ "<item id=\"202\"><name type=\"primary\" value=\"Primary Two\"/></item>"
				+ "<item id=\"203\"><name type=\"primary\" value=\"Primary Three\"/></item>");
		Path report = correctedSql.getParent().resolve("game-name-correction-provenance.json");
		String corrected = Files.readString(correctedSql);
		String provenance = Files.readString(report);

		execute(correctedSql);
		Long originalId = jdbc.queryForObject("select id from games where bgg_id=202", Long.class);
		execute(correctedSql);

		assertEquals(3, count("games"));
		assertEquals(1, count("game_category_relations"));
		assertEquals(originalId, jdbc.queryForObject("select id from games where bgg_id=202", Long.class));
		assertEquals("Primary One", nameOf(201));
		assertEquals("검수 둘", nameOf(202));
		assertEquals("Primary Three", nameOf(203));
		assertTrue(corrected.contains("insert into game_category_relations"));
		assertTrue(provenance.contains("\"inputSqlSha256\": \"" + sha(temp.resolve("input.sql")) + "\""));
		assertTrue(provenance.contains("\"outputSqlSha256\": \"" + sha(correctedSql) + "\""));
		assertTrue(provenance.contains("\"inputRows\": 3"));
		assertTrue(provenance.contains("\"outputRows\": 3"));
	}

	private Path correct(List<GameRow> games, String candidates, String items) throws Exception {
		Path input = temp.resolve("input.sql");
		Path candidateCsv = temp.resolve("candidates.csv");
		Path xmlDirectory = Files.createDirectories(temp.resolve("xml"));
		Path output = temp.resolve("out");
		Files.writeString(input, sql(games));
		Files.writeString(candidateCsv, "bggId,nameEn,nameKo,source,reviewed\n" + candidates);
		Files.writeString(xmlDirectory.resolve("batch.xml"), "<items>" + items + "</items>");

		Process process = new ProcessBuilder(
			"node",
			Path.of(System.getProperty("user.dir"), "scripts/game-catalog/game-name-correction.mjs").toString(),
			"--input-sql", input.toString(),
			"--candidate-csv", candidateCsv.toString(),
			"--xml-directory", xmlDirectory.toString(),
			"--out", output.toString())
			.redirectErrorStream(true)
			.start();
		String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, process.waitFor(), processOutput);
		return output.resolve("01-games-full.sql");
	}

	private String sql(List<GameRow> games) {
		String values = games.stream()
			.map(game -> "(%d, '%s', '%s', '2~4명', '보드게임', '30분', '설명', '상세 설명', current_timestamp, current_timestamp)"
				.formatted(game.bggId(), game.name(), game.englishName()))
			.reduce((left, right) -> left + ",\n" + right)
			.orElseThrow();
		return """
			begin;
			insert into games (bgg_id, name, english_name, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at)
			values
			%s
			on conflict (bgg_id) do update set name=excluded.name, updated_at=current_timestamp;
			insert into game_categories (code, name_ko, name_en, bgg_subdomain, display_order, created_at, updated_at)
			values ('NAME_CORRECTION', '이름 보정', 'Name correction', 'name-correction', 1, current_timestamp, current_timestamp)
			on conflict (code) do update set name_ko=excluded.name_ko;
			insert into game_category_relations (game_id, category_id)
			select games.id, game_categories.id from games cross join game_categories
			where games.bgg_id=%d and game_categories.code='NAME_CORRECTION'
			on conflict do nothing;
			commit;
			""".formatted(values, games.getFirst().bggId());
	}

	private void execute(Path sql) throws Exception {
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
			for (String statementSql : Files.readString(sql).split(";")) {
				if (!statementSql.isBlank()) {
					statement.execute(statementSql);
				}
			}
		}
	}

	private String nameOf(long bggId) {
		return jdbc.queryForObject("select name from games where bgg_id=?", String.class, bggId);
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	private String sha(Path path) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
	}

	private GameRow game(long bggId, String name, String englishName) {
		return new GameRow(bggId, name, englishName);
	}

	private record GameRow(long bggId, String name, String englishName) {
	}
}
