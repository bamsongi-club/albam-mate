package cloud.bamsongi.albammate.matching.service.command;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.matching.MatchProposalResponseAction;
import cloud.bamsongi.albammate.matching.repository.MatchProposalRepository;

@Service
public class MatchProposalResponseService {

	private final MatchProposalResponseExecutor executor;
	private final MatchProposalRepository proposalRepository;

	public MatchProposalResponseService(
		MatchProposalResponseExecutor executor,
		MatchProposalRepository proposalRepository) {
		this.executor = executor;
		this.proposalRepository = proposalRepository;
	}

	public void respond(long userId, long proposalId, MatchProposalResponseAction action, String idempotencyKey) {
		executor.respond(userId, proposalId, action, idempotencyKey);
	}

	public void expireDueProposals() {
		for (Long proposalId : proposalRepository.findDueOpenIds(PageRequest.of(0, 100))) {
			executor.expireIfDue(proposalId);
		}
	}

	public void expireIfDue(long proposalId) {
		executor.expireIfDue(proposalId);
	}
}
