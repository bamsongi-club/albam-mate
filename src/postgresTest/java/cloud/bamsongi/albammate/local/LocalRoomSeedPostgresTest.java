package cloud.bamsongi.albammate.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@ActiveProfiles("local")
class LocalRoomSeedPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String SEED_HOST_EMAIL = "local.seed.host@albammate.local";
	private static final String SEED_TITLE_PREFIX = "[LOCAL] %";
	private static final long LOCAL_GAME_BGG_ID_BASE = -9_000_000_000L;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_local_seed_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void localDatasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("ALBAM_MATE_LOCAL_DB_HOST", postgres::getHost);
		registry.add("ALBAM_MATE_LOCAL_DB_PORT", () -> postgres.getMappedPort(5432));
		registry.add("ALBAM_MATE_LOCAL_DB_NAME", postgres::getDatabaseName);
		registry.add("ALBAM_MATE_LOCAL_DB_USER", postgres::getUsername);
		registry.add("ALBAM_MATE_LOCAL_DB_PASSWORD", postgres::getPassword);
	}

	@Test
	void local_프로필은_Flyway_완료_뒤_공개_시드_60개를_준비한다() {
		assertEquals(30, count("GAME_FOCUSED"));
		assertEquals(30, count("PERSON_FOCUSED"));
		assertEquals(
			30,
			jdbcTemplate.queryForObject(
				"select count(*) from games where bgg_id between ? and ?",
				Integer.class,
				LOCAL_GAME_BGG_ID_BASE - 30,
				LOCAL_GAME_BGG_ID_BASE - 1));
		assertTrue(
			jdbcTemplate.queryForObject(
				"""
					select min(room.start_at)
					from rooms room
					join users host on host.id = room.host_user_id
					where host.email = ? and room.title like ?
					""",
				Instant.class,
				SEED_HOST_EMAIL,
				SEED_TITLE_PREFIX)
				.isAfter(Instant.now()));
	}

	private int count(String roomType) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from rooms room
				join users host on host.id = room.host_user_id
				where host.email = ? and room.title like ? and room.room_type = ?
				""",
			Integer.class,
			SEED_HOST_EMAIL,
			SEED_TITLE_PREFIX,
			roomType);
	}
}
