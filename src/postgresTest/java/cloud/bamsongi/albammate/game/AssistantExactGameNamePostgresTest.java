package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.game.contract.AssistantExactGameNameMatch;
import cloud.bamsongi.albammate.game.contract.AssistantExactGameNameQuery;
import cloud.bamsongi.albammate.game.repository.GameRepository;

@Testcontainers
@SpringBootTest
class AssistantExactGameNamePostgresTest {

	private static final Instant NOW = Instant.parse("2099-01-01T00:00:00Z");
	private static final OffsetDateTime NOW_UTC = NOW.atOffset(ZoneOffset.UTC);
	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
		cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages.postgres18())
		.withDatabaseName("assistant_exact_game_name_test");
	@Autowired
	private AssistantExactGameNameQuery exactGameNameQuery;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private GameRepository gameRepository;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table games restart identity cascade");
	}

	@Test
	void T1_PostgreSQL_id_name_projection_후_유일_ID의_상세_후보를_조회한다() {
		long id = insertGame("카 탄", null, "공개 설명");

		assertEquals(List.of(new AssistantExactGameNameMatch(id, "카 탄")),
			gameRepository.findAssistantExactGameNameMatches());
		var candidate = gameRepository.findAssistantRecommendationCandidateById(id).orElseThrow();
		assertEquals(id, candidate.id());
		assertEquals(null, candidate.imageUrl());
		assertEquals("공개 설명", candidate.description());

		var unique = exactGameNameQuery.findUniqueByNormalizedName("  카\u3000탄  ");
		assertEquals(id, unique.orElseThrow().id());
		assertEquals(null, unique.orElseThrow().imageUrl());
		assertEquals("공개 설명", unique.orElseThrow().description());
	}

	@Test
	void T2_PostgreSQL_복수_정규화_매치는_상세_후보를_조회하지_않는다() {
		insertGame("카 탄", null, "공개 설명");
		insertGame("카\u3000탄", "https://example.com/catan.png", "다른 공개 설명");

		assertEquals(2, gameRepository.findAssistantExactGameNameMatches().size());
		assertTrue(exactGameNameQuery.findUniqueByNormalizedName("카 탄").isEmpty());
		assertTrue(exactGameNameQuery.findUniqueByNormalizedName("카탄!").isEmpty());
	}

	private long insertGame(String name, String imageUrl, String description) {
		long bggId = 9_700_000L + jdbcTemplate.queryForObject("select count(*) from games", Long.class);
		jdbcTemplate.update(
			"""
				insert into games (bgg_id, name, english_name, image_url, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at)
				values (?, ?, 'Catan', ?, '3~4명', '전략', '60분', ?, '상세 설명', ?, ?)
				""",
			bggId, name, imageUrl, description, NOW_UTC, NOW_UTC);
		return jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId);
	}
}
