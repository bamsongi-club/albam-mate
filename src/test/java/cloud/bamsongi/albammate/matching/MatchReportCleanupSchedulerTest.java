package cloud.bamsongi.albammate.matching;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

@SpringBootTest
class MatchReportCleanupSchedulerTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@MockitoBean
	private ScheduledTaskLock scheduledTaskLock;
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
	void G2_공용_task_lock을_획득하지_못하면_cleanup은_상태를_변경하지_않는다() throws Exception {
		long reporterUserId = insertUser("reporter-g2");
		long reportedUserId = insertUser("reported-g2");
		insertReport(reporterUserId, reportedUserId, FIXED_TIME.minusSeconds(1));
		when(scheduledTaskLock.tryExecute(eq("match-report-cleanup"), any(), any()))
			.thenReturn(ScheduledTaskLock.LockExecution.skippedResult());

		invokeCleanupScheduler();

		Integer reportCount = jdbcTemplate.queryForObject("select count(*) from match_reports", Integer.class);
		org.junit.jupiter.api.Assertions.assertEquals(1, reportCount);
		verify(scheduledTaskLock).tryExecute(eq("match-report-cleanup"), any(), any());
	}

	@Test
	void T5_H2_cleanup은_만료된_신고만_삭제하고_반복_실행에도_수렴한다() throws Exception {
		Instant operationTime = Instant.now();
		long expiredReporterUserId = insertUser("expired-reporter");
		long expiredReportedUserId = insertUser("expired-reported");
		long activeReporterUserId = insertUser("active-reporter");
		long activeReportedUserId = insertUser("active-reported");
		insertReport(expiredReporterUserId, expiredReportedUserId, operationTime.minusSeconds(1));
		insertReport(activeReporterUserId, activeReportedUserId, operationTime.plusSeconds(60));
		doAnswer(invocation -> {
			Runnable cleanupTask = invocation.getArgument(2);
			cleanupTask.run();
			return ScheduledTaskLock.LockExecution.acquiredResult();
		}).when(scheduledTaskLock).tryExecute(eq("match-report-cleanup"), any(), any());

		invokeCleanupScheduler();
		invokeCleanupScheduler();

		Integer expiredReportCount = jdbcTemplate.queryForObject(
			"select count(*) from match_reports where reporter_user_id = ? and reported_user_id = ?",
			Integer.class,
			expiredReporterUserId,
			expiredReportedUserId);
		Integer activeReportCount = jdbcTemplate.queryForObject(
			"select count(*) from match_reports where reporter_user_id = ? and reported_user_id = ?",
			Integer.class,
			activeReporterUserId,
			activeReportedUserId);
		org.junit.jupiter.api.Assertions.assertEquals(0, expiredReportCount);
		org.junit.jupiter.api.Assertions.assertEquals(1, activeReportCount);
	}

	private void invokeCleanupScheduler() throws Exception {
		Object scheduler = applicationContext.getBean("matchReportCleanupScheduler");
		Method cleanupMethod = scheduler.getClass().getDeclaredMethod("purgeExpiredReports");
		cleanupMethod.setAccessible(true);
		cleanupMethod.invoke(scheduler);
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
}
