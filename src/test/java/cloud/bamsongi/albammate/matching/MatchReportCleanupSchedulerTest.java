package cloud.bamsongi.albammate.matching;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;
import cloud.bamsongi.albammate.matching.recovery.MatchReportCleanupCoordinator;
import cloud.bamsongi.albammate.matching.recovery.MatchReportCleanupScheduler;

@ExtendWith(MockitoExtension.class)
class MatchReportCleanupSchedulerTest {

	@Mock
	private ScheduledTaskLock scheduledTaskLock;
	@Mock
	private MatchReportCleanupCoordinator coordinator;

	@Test
	void scheduler는_공용_task_lock에_cleanup_coordinator를_위임한다() {
		MatchReportCleanupScheduler scheduler = new MatchReportCleanupScheduler(scheduledTaskLock, coordinator);

		scheduler.purgeExpiredReports();

		ArgumentCaptor<Runnable> cleanupTask = ArgumentCaptor.forClass(Runnable.class);
		verify(scheduledTaskLock).tryExecute(eq("match-report-cleanup"), eq(Duration.ofMinutes(2)),
			cleanupTask.capture());
		cleanupTask.getValue().run();
		verify(coordinator).purgeExpiredReports();
	}
}
