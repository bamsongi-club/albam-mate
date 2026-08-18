package cloud.bamsongi.albammate.matching;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchReportCleanupPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_report_cleanup_test");

	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table match_reports, users restart identity cascade");
	}

	@Test
	void T5_정리는_PostgreSQL에서_만료된_신고만_물리_삭제하고_기한_전_신고를_보존한다() throws Exception {
		Instant operationTime = Instant.now();
		long expiredReporter = insertUser("expired-reporter");
		long expiredReported = insertUser("expired-reported");
		long retainedReporter = insertUser("retained-reporter");
		long retainedReported = insertUser("retained-reported");
		insertReport(expiredReporter, expiredReported, operationTime.minusSeconds(1));
		insertReport(retainedReporter, retainedReported, operationTime.plusSeconds(3600));

		invokeCleanupScheduler();

		Integer expiredCount = jdbcTemplate.queryForObject(
			"select count(*) from match_reports where reporter_user_id = ?", Integer.class, expiredReporter);
		Integer retainedCount = jdbcTemplate.queryForObject(
			"select count(*) from match_reports where reporter_user_id = ?", Integer.class, retainedReporter);
		org.junit.jupiter.api.Assertions.assertEquals(0, expiredCount);
		org.junit.jupiter.api.Assertions.assertEquals(1, retainedCount);
	}

	private void invokeCleanupScheduler() throws Exception {
		Object scheduler = applicationContext.getBean("matchReportCleanupScheduler");
		Method cleanupMethod = scheduler.getClass().getDeclaredMethod("purgeExpiredReports");
		cleanupMethod.setAccessible(true);
		cleanupMethod.invoke(scheduler);
	}

	private long insertUser(String suffix) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, suffix + "@example.com", suffix, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
	}

	private void insertReport(long reporterUserId, long reportedUserId, Instant purgeAfter) {
		jdbcTemplate.update(
			"insert into match_reports (reporter_user_id, reported_user_id, reason, reported_at, purge_after) values (?, ?, 'SPAM_OR_SCAM', ?, ?)",
			reporterUserId, reportedUserId, Timestamp.from(FIXED_TIME.minusSeconds(60)), Timestamp.from(purgeAfter));
	}
}
