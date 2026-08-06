package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

@Testcontainers
class RoomStatusCorrectionProgressLocalMultiPostgresTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("room_status_correction_local_multi_test");

	@Test
	void 새_generation이_진척을_확정한_뒤_이전_실행의_cursor_전진과_wrap을_거절한다() {
		String previousUrl = System.getProperty("spring.datasource.url");
		String previousUsername = System.getProperty("spring.datasource.username");
		String previousPassword = System.getProperty("spring.datasource.password");
		System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
		System.setProperty("spring.datasource.username", POSTGRES.getUsername());
		System.setProperty("spring.datasource.password", POSTGRES.getPassword());
		try {
			try (ConfigurableApplicationContext firstContext = applicationContext();
				ConfigurableApplicationContext secondContext = applicationContext()) {
				RoomStatusCorrectionProgressStore first = firstContext.getBean(RoomStatusCorrectionProgressStore.class);
				RoomStatusCorrectionProgressStore second = secondContext
					.getBean(RoomStatusCorrectionProgressStore.class);
				var staleExecution = first.claimExecution(Instant.parse("2026-08-05T00:01:00Z"));
				var currentExecution = second.claimExecution(Instant.parse("2026-08-05T00:01:00Z"));
				assertTrue(second.advanceCursor(
					currentExecution, Instant.parse("2026-08-05T00:00:30Z"), 20L).isPresent());
				JdbcTemplate jdbcTemplate = secondContext.getBean(JdbcTemplate.class);
				resetProgressVersion(jdbcTemplate, staleExecution.progressVersion());

				assertFalse(first.advanceCursor(
					staleExecution, Instant.parse("2026-08-05T00:00:45Z"), 21L).isPresent());
				resetProgressVersion(jdbcTemplate, staleExecution.progressVersion());
				assertFalse(first.wrap(staleExecution, Instant.parse("2026-08-05T00:02:00Z")).isPresent());

				var persisted = second.current();
				assertEquals(currentExecution.executionGeneration(), persisted.executionGeneration());
				assertEquals(20L, persisted.cursorRoomId());
				assertEquals(staleExecution.progressVersion(), persisted.progressVersion());
			}
		} finally {
			restoreSystemProperty("spring.datasource.url", previousUrl);
			restoreSystemProperty("spring.datasource.username", previousUsername);
			restoreSystemProperty("spring.datasource.password", previousPassword);
		}
	}

	private void resetProgressVersion(JdbcTemplate jdbcTemplate, long progressVersion) {
		jdbcTemplate.update("""
			update room_status_correction_progress
			set progress_version = ?
			where job_name = 'room-status-correction'
			""", progressVersion);
	}

	private ConfigurableApplicationContext applicationContext() {
		return new SpringApplicationBuilder(AlbamMateApplication.class)
			.properties(Map.of(
				"server.port", "0",
				"spring.task.scheduling.enabled", "false",
				"spring.datasource.url", POSTGRES.getJdbcUrl(),
				"spring.datasource.username", POSTGRES.getUsername(),
				"spring.datasource.password", POSTGRES.getPassword(),
				"app.room.status-correction.lock-name", "room-status-correction",
				"app.room.status-correction.trigger-delay", "15m",
				"app.room.status-correction.trigger-jitter", "3m",
				"app.room.status-correction.lock-at-most-for", "2m",
				"app.room.status-correction.execution-warning-threshold", "30s"))
			.run();
	}

	private void restoreSystemProperty(String name, String value) {
		if (value == null) {
			System.clearProperty(name);
			return;
		}
		System.setProperty(name, value);
	}
}
