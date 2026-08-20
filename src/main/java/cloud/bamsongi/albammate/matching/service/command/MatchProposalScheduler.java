package cloud.bamsongi.albammate.matching.service.command;

import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

@Component
public class MatchProposalScheduler {
	static final String LOCK_NAME = "match-proposal-claim";
	private static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(2);

	private final ScheduledTaskLock scheduledTaskLock;
	private final MatchProposalCoordinator coordinator;

	public MatchProposalScheduler(ScheduledTaskLock scheduledTaskLock, MatchProposalCoordinator coordinator) {
		this.scheduledTaskLock = scheduledTaskLock;
		this.coordinator = coordinator;
	}

	@Scheduled(fixedDelay = 60_000)
	public void claimAvailableCandidates() {
		scheduledTaskLock.tryExecute(LOCK_NAME, LOCK_AT_MOST_FOR, coordinator::claimAvailableCandidates);
	}
}
