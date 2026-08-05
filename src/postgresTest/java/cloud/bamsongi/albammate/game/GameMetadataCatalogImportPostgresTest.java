package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.sql.DataSource;

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
class GameMetadataCatalogImportPostgresTest {
	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4");

	@Autowired JdbcTemplate jdbc;
	@Autowired DataSource dataSource;
	@TempDir Path temp;

	@Test
	void renderer_SQL은_두번_적재해도_관계가_중복되지않고_미해결_참조를_전체롤백한다() throws Exception {
		Path sql = prepare().resolve("upsert-game-metadata.sql");
		String script = Files.readString(sql);
		assertTrue(script.startsWith("begin;"));
		assertTrue(script.endsWith("commit;\n"));
		assertTrue(script.contains("albam_mate.allow_test_only_metadata_import"));
		assertTrue(Files.readString(sql.getParent().resolve("service-game-metadata.json")).contains("\"testOnly\":true"));
		seed(100L);
		assertThrows(Exception.class, () -> execute(sql));
		executeTestOnly(sql);
		int approvedCategories = count("game_category_relations");
		assertEquals(2, approvedCategories);
		assertCounts(approvedCategories, 1, 1);
		seedStaleRelations();
		assertCounts(approvedCategories + 1, 2, 2);
		executeTestOnly(sql);
		assertCounts(approvedCategories, 1, 1);
		assertEquals(0, jdbc.queryForObject("select count(*) from game_themes where bgg_theme_id=99", Integer.class));
		jdbc.update("delete from game_theme_relations where theme_id in (select id from game_themes where bgg_theme_id=10)");
		jdbc.update("delete from game_themes where bgg_theme_id=10");
		jdbc.update("insert into game_themes(bgg_theme_id,code,name_ko,name_en,created_at,updated_at) values(199,'FANTASY_BGG_10','오래된 이름','Stale Fantasy',current_timestamp,current_timestamp)");
		executeTestOnly(sql);
		assertCounts(approvedCategories, 1, 1);
		assertEquals(1, jdbc.queryForObject("select count(*) from game_themes where bgg_theme_id=10 and code='FANTASY_BGG_10'", Integer.class));
		assertEquals(0, jdbc.queryForObject("select count(*) from game_themes where bgg_theme_id=199", Integer.class));

		jdbc.update("update game_categories set name_ko='실행전' where code='STRATEGY'");
		jdbc.update("update game_themes set name_ko='실행전' where bgg_theme_id=10");
		jdbc.update("delete from game_category_relations");
		jdbc.update("delete from game_theme_relations");
		jdbc.update("delete from game_player_preferences");
		jdbc.update("delete from games where bgg_id=100");
		assertThrows(Exception.class, () -> executeTestOnly(sql));
		assertCounts(0, 0, 0);
		assertEquals("실행전", jdbc.queryForObject("select name_ko from game_categories where code='STRATEGY'", String.class));
		assertEquals("실행전", jdbc.queryForObject("select name_ko from game_themes where bgg_theme_id=10", String.class));
	}

	private void seedStaleRelations() {
		jdbc.update("insert into game_themes(bgg_theme_id,code,name_ko,name_en,created_at,updated_at) values(99,'STALE','오래됨','Stale',current_timestamp,current_timestamp)");
		jdbc.update("insert into game_category_relations(game_id,category_id) select g.id,c.id from games g join game_categories c on c.code='WARGAME' where g.bgg_id=100");
		jdbc.update("insert into game_theme_relations(game_id,theme_id) select g.id,t.id from games g join game_themes t on t.bgg_theme_id=99 where g.bgg_id=100");
		jdbc.update("insert into game_player_preferences(game_id,player_count,is_recommended,is_best) select id,2,true,false from games where bgg_id=100");
	}

	private Path prepare() throws Exception {
		Path raw = Files.createDirectories(temp.resolve("raw"));
		Path xml = raw.resolve("batch.xml");
		write(xml, "<items><item id=\"100\"><maxplayers value=\"4\"/><link type=\"boardgamecategory\" id=\"10\" value=\"Fantasy\"/><poll name=\"suggested_numplayers\"><results numplayers=\"4\"><result value=\"Best\" numvotes=\"3\"/><result value=\"Recommended\" numvotes=\"1\"/><result value=\"Not Recommended\" numvotes=\"0\"/></results></poll></item></items>");
		Path xmlManifest = temp.resolve("xml-manifest.json");
		write(xmlManifest, "{\"files\":[{\"file\":\"batch.xml\",\"requestIds\":[100],\"responseIds\":[100],\"httpStatus\":200,\"bytes\":" + Files.size(xml) + ",\"sha256\":\"" + sha(xml) + "\",\"acquiredAt\":\"2026-08-05T00:00:00Z\"}]}");
		Path games = temp.resolve("games.json");
		write(games, "[{\"bgg_id\":100}]");
		Path ranks = temp.resolve("ranks.csv");
		write(ranks, "id,name,yearpublished,rank,bayesaverage,average,usersrated,is_expansion,abstracts_rank,cgs_rank,childrensgames_rank,familygames_rank,partygames_rank,strategygames_rank,thematic_rank,wargames_rank\n100,x,2020,1,1,1,1,0,0,0,0,1,0,1,0,0\n");
		Path dictionary = temp.resolve("themes.json");
		write(dictionary, "{\"entries\":[{\"bggThemeId\":10,\"nameEn\":\"Fantasy\",\"nameKo\":\"판타지\"}]}");
		Path manifest = temp.resolve("metadata-input-manifest.json");
		write(manifest, "{\"schemaVersion\":1,\"approved\":true,\"testOnly\":true,\"games\":{\"path\":\"" + games + "\",\"sha256\":\"" + sha(games) + "\",\"rows\":1},\"ranks\":{\"path\":\"" + ranks + "\",\"sha256\":\"" + sha(ranks) + "\"},\"xmlSnapshot\":{\"rawDirectory\":\"" + raw + "\",\"manifestPath\":\"" + xmlManifest + "\",\"manifestSha256\":\"" + sha(xmlManifest) + "\"},\"themeDictionary\":{\"path\":\"" + dictionary + "\",\"sha256\":\"" + sha(dictionary) + "\"},\"reviewedBy\":\"test\",\"reviewedAt\":\"2026-08-05T00:00:00Z\"}");
		Path out = temp.resolve("out");
		Process process = new ProcessBuilder("node", Path.of(System.getProperty("user.dir"), "scripts/game-catalog/prepare-game-metadata-catalog.mjs").toString(), "--input-manifest", manifest.toString(), "--out", out.toString()).redirectErrorStream(true).start();
		int exit = process.waitFor();
		if (exit != 0) throw new AssertionError(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8) + Files.readString(out.resolve("quality-report.json")));
		return out;
	}

	private void seed(long bggId) {
		jdbc.update("insert into games(bgg_id,name,english_name,supported_player_count,tag,estimated_play_time,description,detail_description,created_at,updated_at) values(?,?,?,?,?,?,?,?,current_timestamp,current_timestamp)", bggId, "test", "test", "2~4", "tag", "30", "d", "d");
	}

	private void execute(Path sql) throws Exception {
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
			execute(statement, sql);
		}
	}

	private void executeTestOnly(Path sql) throws Exception {
		try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
			statement.execute("set albam_mate.allow_test_only_metadata_import='true'");
			try {
				execute(statement, sql);
			} finally {
				statement.execute("reset albam_mate.allow_test_only_metadata_import");
			}
		}
	}

	private void execute(java.sql.Statement statement, Path sql) throws Exception {
		try {
			for (String part : splitSql(Files.readString(sql))) if (!part.isBlank()) statement.execute(part);
		} catch (Exception error) {
			statement.execute("rollback");
			throw error;
		}
	}

	private static java.util.List<String> splitSql(String sql) {
		var parts = new java.util.ArrayList<String>();
		var current = new StringBuilder();
		boolean dollar = false;
		for (int i = 0; i < sql.length(); i++) {
			if (sql.startsWith("$$", i)) { dollar = !dollar; current.append("$$"); i++; continue; }
			char ch = sql.charAt(i);
			if (ch == ';' && !dollar) { parts.add(current.toString()); current.setLength(0); } else current.append(ch);
		}
		if (!current.isEmpty()) parts.add(current.toString());
		return parts;
	}

	private void assertCounts(int categories, int themes, int preferences) {
		assertEquals(categories, count("game_category_relations"), "categories");
		assertEquals(themes, count("game_theme_relations"), "themes");
		assertEquals(preferences, count("game_player_preferences"), "preferences");
	}

	private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
	private void write(Path path, String value) throws Exception { Files.writeString(path, value); }
	private String sha(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
