package cloud.bamsongi.albammate.matching.recovery;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseService;

@Service
public class MatchRecoveryCoordinator {

	private static final int LIFECYCLE_CANDIDATE_BATCH_SIZE = 100;

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
		recoverLifecycleCandidates();
		retentionCleanupExecutor.cleanUpExpiredRecords();
	}

	private void recoverLifecycleCandidates() {
		long afterPartyId = 0;
		while (true) {
			java.util.List<Long> candidateIds = partyRepository
				.findLifecycleCandidateIdsAfter(afterPartyId, LIFECYCLE_CANDIDATE_BATCH_SIZE);
			if (candidateIds.isEmpty()) {
				return;
			}
			for (Long partyId : candidateIds) {
				partyRepository.findById(partyId).ifPresent(this::recoverParty);
			}
			afterPartyId = candidateIds.getLast();
			if (candidateIds.size() < LIFECYCLE_CANDIDATE_BATCH_SIZE) {
				return;
			}
		}
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
