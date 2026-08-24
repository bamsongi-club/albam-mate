package cloud.bamsongi.albammate.matching.service.command;

import org.springframework.stereotype.Service;

@Service
public class MatchProposalCoordinator {

	private final MatchProposalExecutor executor;

	public MatchProposalCoordinator(MatchProposalExecutor executor) {
		this.executor = executor;
	}

	public void claimAvailableCandidates() {
		executor.claimAvailableCandidates();
	}
}
