package cloud.bamsongi.albammate.matching.recovery;

import java.time.Duration;
import java.util.Objects;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

@Component
public class MatchReportCleanupScheduler {

	static final String LOCK_NAME = "match-report-cleanup";
	private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);

	private final ScheduledTaskLock scheduledTaskLock;
	private final MatchReportCleanupCoordinator coordinator;

	public MatchReportCleanupScheduler(
		ScheduledTaskLock scheduledTaskLock,
		MatchReportCleanupCoordinator coordinator) {
		this.scheduledTaskLock = Objects.requireNonNull(scheduledTaskLock, "scheduledTaskLock");
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
	}

	@Scheduled(cron = "${app.match.report-cleanup.cron:0 30 3 * * *}", zone = "UTC")
	public void purgeExpiredReports() {
		scheduledTaskLock.tryExecute(LOCK_NAME, LOCK_AT_MOST_FOR, coordinator::purgeExpiredReports);
	}
}
