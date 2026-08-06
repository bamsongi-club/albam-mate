package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@EnabledIfSystemProperty(named = "issue420.fixture", matches = ".+")
class GameMetadataSearchPerformancePostgresTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	DataSource dataSource;
	@Autowired
	ObjectMapper objectMapper;

	@Test
	void 십칠만건_fixture에서_대표조합의_결과_전체건수_실행계획과_시간을_기록한다() throws Exception {
		Path fixture = Path.of(required("issue420.fixture"));
		Path manifest = Path.of(required("issue420.fixtureManifest"));
		Path ranks = Path.of(required("issue420.rankCsv"));
		Path report = Path.of(required("issue420.performanceReport"));
		String fixtureSha = sha256(Files.readAllBytes(fixture));
		String rankSha = sha256(Files.readAllBytes(ranks));
		JsonNode sourceManifest = objectMapper.readTree(Files.readString(manifest));
		assertEquals("performance-fixture-only", sourceManifest.path("classification").asText());
		assertFalse(sourceManifest.path("productionImportApproved").asBoolean(true));
		assertEquals(170000, sourceManifest.path("output").path("rows").asInt());
		assertEquals(fixture.toAbsolutePath().normalize().toString(),
			sourceManifest.path("output").path("path").asText());
		assertEquals(fixtureSha, sourceManifest.path("output").path("sha256").asText());
		assertEquals(ranks.toAbsolutePath().normalize().toString(),
			sourceManifest.path("sources").path("ranks").path("path").asText());
		assertEquals(rankSha, sourceManifest.path("sources").path("ranks").path("sha256").asText());
		assertEquals(170000, loadFixture(fixture));
		seedCategories(ranks);
		seedPerformanceOnlyRelations();
		assertEquals(170000L, jdbc.queryForObject("select count(*) from games", Long.class));
		List<Scenario> scenarios = List.of(
			new Scenario("no-filter", "true"),
			new Scenario("category-single",
				"exists (select 1 from game_category_relations r join game_categories c on c.id=r.category_id where r.game_id=g.id and c.code='STRATEGY')"),
			new Scenario("category-or",
				"exists (select 1 from game_category_relations r join game_categories c on c.id=r.category_id where r.game_id=g.id and c.code in ('STRATEGY','FAMILY'))"),
			new Scenario("theme-any-single",
				"exists (select 1 from game_theme_relations r join game_themes t on t.id=r.theme_id where r.game_id=g.id and t.code='THEME_A')"),
			new Scenario("theme-any-multiple",
				"exists (select 1 from game_theme_relations r join game_themes t on t.id=r.theme_id where r.game_id=g.id and t.code in ('THEME_A','THEME_B'))"),
			new Scenario("theme-all-multiple",
				"(select count(distinct t.code) from game_theme_relations r join game_themes t on t.id=r.theme_id where r.game_id=g.id and t.code in ('THEME_A','THEME_B'))=2"),
			new Scenario("compound",
				"exists (select 1 from game_category_relations r join game_categories c on c.id=r.category_id where r.game_id=g.id and c.code in ('STRATEGY','FAMILY')) and exists (select 1 from game_theme_relations r join game_themes t on t.id=r.theme_id where r.game_id=g.id and t.code='THEME_A') and exists (select 1 from game_mechanism_relations mr join game_mechanisms m on m.id=mr.mechanism_id where mr.game_id=g.id and m.code='PERF_MECHANISM') and exists (select 1 from game_player_preferences p where p.game_id=g.id and p.is_recommended and p.player_count in (3,4)) and exists (select 1 from game_player_preferences p where p.game_id=g.id and p.is_best and p.player_count in (4,5)) and g.min_players<=2 and g.max_players>=4"));
		StringBuilder json = new StringBuilder("{\"fixtureSha256\":\"").append(fixtureSha)
			.append("\",\"rankSha256\":\"").append(rankSha).append("\",\"fixtureManifestSha256\":\"")
			.append(sha256(Files.readAllBytes(manifest)))
			.append("\",\"rows\":170000,\"performanceFixtureRelations\":true,\"queries\":[");
		for (int i = 0; i < scenarios.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append(run(scenarios.get(i)));
		}
		json.append("]}");
		Files.writeString(report, json.toString());
		String reportJson = Files.readString(report);
		assertTrue(reportJson.contains("compound"));
		assertTrue(reportJson.contains("\"pageAndCountElapsedMs\":"));
		assertTrue(reportJson.contains("\"explainAnalyzeElapsedMs\":"));
		assertFalse(reportJson.contains("\"elapsedMs\":"));
	}

	private static String required(String key) {
		String value = System.getProperty(key);
		if (value == null) {
			value = System.getenv(key.replace('.', '_').toUpperCase(Locale.ROOT));
		}
		assertNotNull(value, "-D" + key + " required");
		return value;
	}

	private record Scenario(String name, String predicate) {
	}

	private String run(Scenario scenario) {
		String page = "select g.id from games g where " + scenario.predicate + " order by g.name,g.id limit 10";
		String count = "select count(*) from games g where " + scenario.predicate;
		long pageAndCountStart = System.nanoTime();
		List<Long> ids = jdbc.queryForList(page, Long.class);
		Long total = jdbc.queryForObject(count, Long.class);
		long pageAndCountElapsed = (System.nanoTime() - pageAndCountStart) / 1_000_000;
		long explainAnalyzeStart = System.nanoTime();
		String plan = jdbc.queryForObject("explain (analyze,buffers,format json) " + page, String.class);
		long explainAnalyzeElapsed = (System.nanoTime() - explainAnalyzeStart) / 1_000_000;
		assertTrue(ids.size() <= total);
		return "{\"name\":\"" + scenario.name + "\",\"pageIds\":" + ids + ",\"total\":" + total
			+ ",\"pageAndCountElapsedMs\":" + pageAndCountElapsed + ",\"explainAnalyzeElapsedMs\":"
			+ explainAnalyzeElapsed + ",\"explain\":\"" + escape(plan) + "\"}";
	}

	private void seedCategories(Path ranks) throws Exception {
		String[][] categories = {{"STRATEGY", "전략", "Strategy", "strategygames"},
			{"ABSTRACT_STRATEGY", "추상 전략", "Abstract", "abstracts"}, {"COLLECTIBLE", "컬렉터블", "Collectible", "cgs"},
			{"FAMILY", "가족", "Family", "familygames"}, {"CHILDREN", "어린이", "Children", "childrensgames"},
			{"THEMATIC", "테마", "Thematic", "thematic"}, {"PARTY", "파티", "Party", "partygames"},
			{"WARGAME", "워게임", "Wargame", "wargames"}};
		int[] rankColumn = {5, 0, 1, 3, 2, 6, 4, 7};
		for (int i = 0; i < categories.length; i++) {
			jdbc.update(
				"insert into game_categories(code,name_ko,name_en,bgg_subdomain,display_order,created_at,updated_at) values(?,?,?,?,?,current_timestamp,current_timestamp)",
				categories[i][0], categories[i][1], categories[i][2], categories[i][3], i + 1);
		}
		try (var c = dataSource.getConnection();
			var s = c.prepareStatement(
				"insert into game_category_relations(game_id,category_id) select g.id,cat.id from games g join game_categories cat on cat.code=? where g.bgg_id=? on conflict do nothing");
			var reader = Files.newBufferedReader(ranks)) {
			String line;
			reader.readLine();
			int batch = 0;
			while ((line = reader.readLine()) != null) {
				String[] p = line.split(",", -1);
				if (p.length < 9) {
					continue;
				}
				long id = Long.parseLong(p[0]);
				for (int i = 0; i < 8; i++) {
					String rank = p[p.length - 8 + rankColumn[i]];
					if (rank.matches("[1-9]\\d*")) {
						s.setString(1, categories[i][0]);
						s.setLong(2, id);
						s.addBatch();
						if (++batch == 500) {
							s.executeBatch();
							batch = 0;
						}
					}
				}
			}
			if (batch > 0) {
				s.executeBatch();
			}
		}
	}

	private void seedPerformanceOnlyRelations() {
		jdbc.update(
			"insert into game_themes(bgg_theme_id,code,name_ko,name_en,created_at,updated_at) values(9001,'THEME_A','성능A','Performance A',current_timestamp,current_timestamp),(9002,'THEME_B','성능B','Performance B',current_timestamp,current_timestamp)");
		jdbc.update(
			"insert into game_theme_relations(game_id,theme_id) select g.id,t.id from games g join game_themes t on t.code='THEME_A' where mod(g.bgg_id,2)=0");
		jdbc.update(
			"insert into game_theme_relations(game_id,theme_id) select g.id,t.id from games g join game_themes t on t.code='THEME_B' where mod(g.bgg_id,3)=0");
		jdbc.update(
			"insert into game_player_preferences(game_id,player_count,is_recommended,is_best) select id,4,true,true from games where mod(bgg_id,3)=0");
		jdbc.update(
			"insert into game_player_preferences(game_id,player_count,is_recommended,is_best) select id,3,true,false from games where mod(bgg_id,5)=0 on conflict do nothing");
		jdbc.update(
			"insert into game_mechanisms(bgg_mechanism_id,code,name_ko,name_en,is_public,source_reference,reviewed_by,reviewed_at,created_at,updated_at) values(999999,'PERF_MECHANISM','성능 메커니즘','Performance Mechanism',true,'performance-only','test',current_timestamp,current_timestamp,current_timestamp)");
		jdbc.update(
			"insert into game_mechanism_relations(game_id,mechanism_id) select g.id,m.id from games g join game_mechanisms m on m.code='PERF_MECHANISM' where mod(g.bgg_id,3)=0");
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	private int loadFixture(Path fixture) throws Exception {
		String sql = "insert into games(bgg_id,name,english_name,alias,image_url,supported_player_count,min_players,max_players,tag,estimated_play_time,complexity,description,detail_description,created_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
		try (var connection = dataSource.getConnection();
			var statement = connection.prepareStatement(sql);
			var reader = Files.newBufferedReader(fixture)) {
			int count = 0, batch = 0;
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("{")) {
					continue;
				}
				Map<String, String> row = parse(line);
				if (!row.containsKey("bgg_id") || !row.containsKey("min_players") || !row.containsKey("max_players")) {
					throw new IllegalArgumentException("missing fixture player range");
				}
				statement.setLong(1, Long.parseLong(row.get("bgg_id")));
				statement.setString(2, row.get("name"));
				statement.setString(3, row.get("english_name"));
				statement.setString(4, row.get("alias"));
				statement.setString(5, row.get("image_url"));
				statement.setString(6, row.get("supported_player_count"));
				statement.setInt(7, Integer.parseInt(row.get("min_players")));
				statement.setInt(8, Integer.parseInt(row.get("max_players")));
				statement.setString(9, row.get("tag"));
				statement.setString(10, row.get("estimated_play_time"));
				if (row.get("complexity") == null || row.get("complexity").equals("null")) {
					statement.setNull(11, java.sql.Types.NUMERIC);
				} else {
					statement.setBigDecimal(11, new java.math.BigDecimal(row.get("complexity")));
				}
				statement.setString(12, row.get("description"));
				statement.setString(13, row.get("detail_description"));
				statement.setTimestamp(14, java.sql.Timestamp.from(Instant.EPOCH));
				statement.setTimestamp(15, java.sql.Timestamp.from(Instant.EPOCH));
				statement.addBatch();
				count++;
				if (++batch == 500) {
					statement.executeBatch();
					batch = 0;
				}
			}
			if (batch > 0) {
				statement.executeBatch();
			}
			return count;
		}
	}

	private static Map<String, String> parse(String line) {
		Map<String, String> values = new HashMap<>();
		for (String key : List.of("bgg_id", "name", "english_name", "alias", "image_url", "supported_player_count",
			"min_players", "max_players", "tag", "estimated_play_time", "complexity", "description",
			"detail_description")) {
			java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("\\\"" + key + "\\\":(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|([^,}]+))").matcher(line);
			if (matcher.find()) {
				values.put(key, (matcher.group(1) != null ? matcher.group(1) : matcher.group(2)).replace("\\\"", "\"")
					.replace("\\\\", "\\"));
			}
		}
		return values;
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}
}
