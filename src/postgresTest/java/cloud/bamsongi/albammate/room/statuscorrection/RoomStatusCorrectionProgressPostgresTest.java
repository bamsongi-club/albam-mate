package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

@Testcontainers
@SpringBootTest
class RoomStatusCorrectionProgressPostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("room_status_correction_progress_test");

	@Autowired
	private RoomStatusCorrectionProgressRepository progressRepository;
	@Autowired
	private RoomStatusCorrectionProgressStore progressStore;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void progress를_초기_상태로_되돌린다() {
		jdbcTemplate.update("""
			update room_status_correction_progress
			set turn_cutoff = null,
			    cursor_due_at = null,
			    cursor_room_id = null,
			    progress_version = 0,
			    execution_generation = 0
			where job_name = 'room-status-correction'
			""");
	}

	@Test
	void 현재_progress_한_행을_재시작_뒤에도_초기화하지_않고_다시_읽는다() {
		var cutoff = Instant.parse("2026-08-05T00:00:00Z");
		var cursorDueAt = Instant.parse("2026-08-04T23:00:00Z");
		String previousUrl = System.getProperty("spring.datasource.url");
		String previousUsername = System.getProperty("spring.datasource.username");
		String previousPassword = System.getProperty("spring.datasource.password");
		System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
		System.setProperty("spring.datasource.username", POSTGRES.getUsername());
		System.setProperty("spring.datasource.password", POSTGRES.getPassword());
		try {
			try (ConfigurableApplicationContext firstContext = applicationContext()) {
				RoomStatusCorrectionProgressStore first = firstContext.getBean(RoomStatusCorrectionProgressStore.class);
				var claimed = first.claimExecution(cutoff);
				var advanced = first.advanceCursor(claimed, cursorDueAt, 42L).orElseThrow();
				assertEquals(2L, advanced.progressVersion());
				assertEquals(1L, advanced.executionGeneration());
			}

			try (ConfigurableApplicationContext restartedContext = applicationContext()) {
				RoomStatusCorrectionProgressStore restarted = restartedContext
					.getBean(RoomStatusCorrectionProgressStore.class);
				var persisted = restarted.current();

				assertEquals(1, progressRepository.count());
				assertEquals(cutoff, persisted.turnCutoff());
				assertEquals(cursorDueAt, persisted.cursorDueAt());
				assertEquals(42L, persisted.cursorRoomId());
				assertEquals(2L, persisted.progressVersion());
				assertEquals(1L, persisted.executionGeneration());
			}
		} finally {
			restoreSystemProperty("spring.datasource.url", previousUrl);
			restoreSystemProperty("spring.datasource.username", previousUsername);
			restoreSystemProperty("spring.datasource.password", previousPassword);
		}
	}

	@Test
	void 기대_version과_실행_generation이_같을_때만_cursor_전진과_wrap을_원자적으로_저장한다() {
		var claimed = progressStore.claimExecution(Instant.parse("2026-08-05T00:00:00Z"));
		var advanced = progressStore.advanceCursor(
			claimed, Instant.parse("2026-08-04T23:00:00Z"), 42L).orElseThrow();
		assertTrue(progressStore.advanceCursor(
			claimed, Instant.parse("2026-08-04T23:30:00Z"), 43L).isEmpty());
		assertTrue(progressStore.wrap(claimed, Instant.parse("2026-08-05T01:00:00Z")).isEmpty());
		var persistedBeforeWrap = progressStore.current();
		assertEquals(advanced.progressVersion(), persistedBeforeWrap.progressVersion());
		assertEquals(advanced.executionGeneration(), persistedBeforeWrap.executionGeneration());
		assertEquals(42L, persistedBeforeWrap.cursorRoomId());
		var wrapped = progressStore.wrap(
			advanced, Instant.parse("2026-08-05T01:00:00Z")).orElseThrow();

		RoomStatusCorrectionProgress progress = progressRepository.findCurrent();
		assertEquals(1L, claimed.executionGeneration());
		assertEquals(2L, advanced.progressVersion());
		assertTrue(progress.getTurnCutoff().equals(Instant.parse("2026-08-05T01:00:00Z")));
		assertNull(progress.getCursorDueAt());
		assertNull(progress.getCursorRoomId());
		assertEquals(3L, wrapped.progressVersion());
		assertEquals(wrapped.progressVersion(), progress.getProgressVersion());
	}

	@Test
	void cursor_전진은_양수가_아닌_ROOM_ID를_거부한다() {
		var claimed = progressStore.claimExecution(Instant.parse("2026-08-05T00:00:00Z"));

		assertThrows(IllegalArgumentException.class, () -> progressStore.advanceCursor(
			claimed, Instant.parse("2026-08-04T23:00:00Z"), 0L));
	}

	@Test
	void cursor_전진은_turn_cutoff가_없는_실행을_거부한다() {
		var snapshotWithoutCutoff = new RoomStatusCorrectionProgressStore.ProgressSnapshot(
			null, null, null, 0L, 0L);

		assertThrows(IllegalArgumentException.class, () -> progressStore.advanceCursor(
			snapshotWithoutCutoff, Instant.parse("2026-08-04T23:00:00Z"), 42L));
	}

	@Test
	void cursor_전진은_현재_turn_cutoff_뒤의_due_at을_거부한다() {
		var claimed = progressStore.claimExecution(Instant.parse("2026-08-05T00:00:00Z"));

		assertThrows(IllegalArgumentException.class, () -> progressStore.advanceCursor(
			claimed, Instant.parse("2026-08-05T00:00:00.000000001Z"), 42L));
	}

	@Test
	void cursor가_있으면_turn_cutoff과_양수_ROOM_ID를_강제한다() {
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
			update room_status_correction_progress
			set cursor_due_at = ?, cursor_room_id = ?
			where job_name = 'room-status-correction'
			""", Timestamp.from(Instant.parse("2026-08-04T23:00:00Z")), 1L));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update("""
			update room_status_correction_progress
			set turn_cutoff = ?, cursor_due_at = ?, cursor_room_id = ?
			where job_name = 'room-status-correction'
			""", Timestamp.from(Instant.parse("2026-08-05T00:00:00Z")),
			Timestamp.from(Instant.parse("2026-08-04T23:00:00Z")), 0L));
	}

	@Test
	void progress_변경의_updatedAt은_애플리케이션_시각이_아닌_PostgreSQL_기준_시각이다() {
		Instant databaseBefore = jdbcTemplate.queryForObject("select current_timestamp", Instant.class);
		Instant applicationTime = Instant.parse("2000-01-01T00:00:00Z");
		try (MockedStatic<Instant> instant = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
			instant.when(Instant::now).thenReturn(applicationTime);
			var claimed = progressStore.claimExecution(Instant.parse("2026-08-05T00:00:00Z"));
			var advanced = progressStore.advanceCursor(
				claimed, Instant.parse("2026-08-04T23:00:00Z"), 42L).orElseThrow();
			progressStore.wrap(advanced, Instant.parse("2026-08-05T01:00:00Z")).orElseThrow();
		}

		Instant updatedAt = jdbcTemplate.queryForObject(
			"select updated_at from room_status_correction_progress where job_name = 'room-status-correction'",
			Instant.class);
		assertTrue(updatedAt.isAfter(databaseBefore));
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
