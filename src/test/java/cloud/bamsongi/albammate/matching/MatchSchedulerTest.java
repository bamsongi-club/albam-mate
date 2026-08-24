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
import cloud.bamsongi.albammate.matching.recovery.MatchRecoveryCoordinator;
import cloud.bamsongi.albammate.matching.recovery.MatchRecoveryScheduler;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalCoordinator;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalScheduler;

@ExtendWith(MockitoExtension.class)
class MatchSchedulerTest {

	@Mock
	private ScheduledTaskLock scheduledTaskLock;
	@Mock
	private MatchProposalCoordinator proposalCoordinator;
	@Mock
	private MatchRecoveryCoordinator recoveryCoordinator;

	@Test
	void proposal_scheduler는_task_lock을_얻은_경우에만_claim_coordinator를_호출한다() {
		MatchProposalScheduler scheduler = new MatchProposalScheduler(scheduledTaskLock, proposalCoordinator);

		scheduler.claimAvailableCandidates();

		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(scheduledTaskLock).tryExecute(eq("match-proposal-claim"), eq(Duration.ofMinutes(2)), task.capture());
		task.getValue().run();
		verify(proposalCoordinator).claimAvailableCandidates();
	}

	@Test
	void recovery_scheduler는_task_lock을_얻은_경우에만_recovery_coordinator를_호출한다() {
		MatchRecoveryScheduler scheduler = new MatchRecoveryScheduler(scheduledTaskLock, recoveryCoordinator);

		scheduler.recoverDueParties();

		ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(scheduledTaskLock).tryExecute(eq("match-recovery"), eq(Duration.ofMinutes(2)), task.capture());
		task.getValue().run();
		verify(recoveryCoordinator).recoverDueParties();
	}
}
