package cloud.bamsongi.albammate.matching.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(MatchReportCleanupCoordinatorIntegrationTest.FixedClockConfiguration.class)
class MatchReportCleanupCoordinatorIntegrationTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private MatchReportCleanupCoordinator coordinator;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	private final List<Long> createdUserIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		for (Long userId : createdUserIds) {
			jdbcTemplate.update("delete from match_reports where reporter_user_id = ? or reported_user_id = ?", userId,
				userId);
			jdbcTemplate.update("delete from users where id = ?", userId);
		}
	}

	@Test
	void H2_cleanup은_만료된_신고만_삭제하고_반복_실행에도_수렴한다() {
		long expiredReporterUserId = insertUser("expired-reporter");
		long expiredReportedUserId = insertUser("expired-reported");
		long activeReporterUserId = insertUser("active-reporter");
		long activeReportedUserId = insertUser("active-reported");
		insertReport(expiredReporterUserId, expiredReportedUserId, FIXED_TIME.minusSeconds(1));
		insertReport(activeReporterUserId, activeReportedUserId, FIXED_TIME.plusSeconds(60));

		coordinator.purgeExpiredReports();
		coordinator.purgeExpiredReports();

		assertEquals(0, reportCount(expiredReporterUserId, expiredReportedUserId));
		assertEquals(1, reportCount(activeReporterUserId, activeReportedUserId));
	}

	private long insertUser(String suffix) {
		String uniqueSuffix = suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			uniqueSuffix + "@example.com", uniqueSuffix, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
		Long userId = jdbcTemplate.queryForObject("select max(id) from users", Long.class);
		createdUserIds.add(userId);
		return userId;
	}

	private void insertReport(long reporterUserId, long reportedUserId, Instant purgeAfter) {
		jdbcTemplate.update(
			"insert into match_reports (reporter_user_id, reported_user_id, reason, reported_at, purge_after) values (?, ?, 'SPAM_OR_SCAM', ?, ?)",
			reporterUserId, reportedUserId, Timestamp.from(FIXED_TIME.minusSeconds(60)), Timestamp.from(purgeAfter));
	}

	private int reportCount(long reporterUserId, long reportedUserId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from match_reports where reporter_user_id = ? and reported_user_id = ?", Integer.class,
			reporterUserId, reportedUserId);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
		}
	}
}
