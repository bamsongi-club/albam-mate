package cloud.bamsongi.albammate.matching.recovery;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

@Component
public class MatchRecoveryScheduler {
	static final String LOCK_NAME = "match-recovery";
	private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);

	private final ScheduledTaskLock scheduledTaskLock;
	private final MatchRecoveryCoordinator coordinator;

	public MatchRecoveryScheduler(ScheduledTaskLock scheduledTaskLock, MatchRecoveryCoordinator coordinator) {
		this.scheduledTaskLock = scheduledTaskLock;
		this.coordinator = coordinator;
	}

	@Scheduled(fixedDelay = 60_000)
	public void recoverDueParties() {
		scheduledTaskLock.tryExecute(LOCK_NAME, LOCK_AT_MOST_FOR, coordinator::recoverDueParties);
	}
}
