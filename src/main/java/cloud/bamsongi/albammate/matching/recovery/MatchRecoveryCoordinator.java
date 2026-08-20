package cloud.bamsongi.albammate.matching.recovery;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseService;

@Service
public class MatchRecoveryCoordinator {

	private final MatchProposalResponseService proposalResponseService;
	private final MatchPartyRepository partyRepository;
	private final MatchPartyLifecycleExecutor lifecycleExecutor;
	private final MatchPreparingRecoveryExecutor preparingRecoveryExecutor;
	private final MatchClosedPartyCleanupExecutor closedPartyCleanupExecutor;
	private final MatchRetentionCleanupExecutor retentionCleanupExecutor;

	public MatchRecoveryCoordinator(
		MatchProposalResponseService proposalResponseService,
		MatchPartyRepository partyRepository,
		MatchPartyLifecycleExecutor lifecycleExecutor,
		MatchPreparingRecoveryExecutor preparingRecoveryExecutor,
		MatchClosedPartyCleanupExecutor closedPartyCleanupExecutor,
		MatchRetentionCleanupExecutor retentionCleanupExecutor) {
		this.proposalResponseService = proposalResponseService;
		this.partyRepository = partyRepository;
		this.lifecycleExecutor = lifecycleExecutor;
		this.preparingRecoveryExecutor = preparingRecoveryExecutor;
		this.closedPartyCleanupExecutor = closedPartyCleanupExecutor;
		this.retentionCleanupExecutor = retentionCleanupExecutor;
	}

	public void recoverDueParties() {
		proposalResponseService.expireDueProposals();
		for (Long partyId : partyRepository.findLifecycleCandidateIds()) {
			partyRepository.findById(partyId).ifPresent(this::recoverParty);
		}
		retentionCleanupExecutor.cleanUpExpiredRecords();
	}

	private void recoverParty(cloud.bamsongi.albammate.matching.entity.MatchParty party) {
		if (party.getStatus() == MatchPartyStatus.PREPARING) {
			preparingRecoveryExecutor.recover(party.getId());
			return;
		}
		if (party.getStatus() == MatchPartyStatus.CLOSED) {
			closedPartyCleanupExecutor.cleanUp(party.getId());
			return;
		}
		if (party.getStatus() == MatchPartyStatus.ACTIVE) {
			lifecycleExecutor.recover(party.getId());
		}
	}
}
