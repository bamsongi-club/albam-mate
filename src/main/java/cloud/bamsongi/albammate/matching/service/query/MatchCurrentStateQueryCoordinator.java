package cloud.bamsongi.albammate.matching.service.query;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.MatchRequestStatus;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.entity.MatchParty;
import cloud.bamsongi.albammate.matching.recovery.MatchPartyLifecycleExecutor;
import cloud.bamsongi.albammate.matching.recovery.MatchPreparingRecoveryExecutor;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalMemberRepository;
import cloud.bamsongi.albammate.matching.repository.MatchRequestRepository;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseService;

@Service
public class MatchCurrentStateQueryCoordinator {

	private final MatchCurrentStateReadService readService;
	private final MatchPartyRepository partyRepository;
	private final MatchPartyLifecycleExecutor partyLifecycleExecutor;
	private final MatchPreparingRecoveryExecutor preparingRecoveryExecutor;
	private final MatchRequestRepository requestRepository;
	private final MatchProposalMemberRepository proposalMemberRepository;
	private final MatchProposalResponseService proposalResponseService;

	public MatchCurrentStateQueryCoordinator(
		MatchCurrentStateReadService readService,
		MatchPartyRepository partyRepository,
		MatchPartyLifecycleExecutor partyLifecycleExecutor,
		MatchPreparingRecoveryExecutor preparingRecoveryExecutor,
		MatchRequestRepository requestRepository,
		MatchProposalMemberRepository proposalMemberRepository,
		MatchProposalResponseService proposalResponseService) {
		this.readService = readService;
		this.partyRepository = partyRepository;
		this.partyLifecycleExecutor = partyLifecycleExecutor;
		this.preparingRecoveryExecutor = preparingRecoveryExecutor;
		this.requestRepository = requestRepository;
		this.proposalMemberRepository = proposalMemberRepository;
		this.proposalResponseService = proposalResponseService;
	}

	public CurrentMatchStateResponse read(long userId) {
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				repairDueState(userId);
				return readService.read(userId);
			} catch (BusinessException exception) {
				if (exception.getErrorCode() != ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE || attempt == 2) {
					throw exception;
				}
			}
		}
		throw new BusinessException(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE);
	}

	private void repairDueState(long userId) {
		requestRepository.findCurrentByUserId(userId)
			.filter(request -> request.getStatus() == MatchRequestStatus.PROPOSED)
			.flatMap(request -> proposalMemberRepository.findByMatchRequestId(request.getId()))
			.map(member -> member.getId().getProposalId())
			.ifPresent(proposalResponseService::expireIfDue);
		partyRepository.findCurrentByUserId(userId).ifPresent(this::repairPartyLifecycle);
	}

	private void repairPartyLifecycle(MatchParty party) {
		if (party.getStatus() == cloud.bamsongi.albammate.matching.MatchPartyStatus.PREPARING) {
			preparingRecoveryExecutor.recover(party.getId());
			return;
		}
		if (party.getStatus() == cloud.bamsongi.albammate.matching.MatchPartyStatus.ACTIVE) {
			partyLifecycleExecutor.recover(party.getId());
		}
	}
}
