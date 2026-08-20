package cloud.bamsongi.albammate.matching.service.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.MatchProposalResponseStatus;
import cloud.bamsongi.albammate.matching.entity.MatchProposal;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMember;
import cloud.bamsongi.albammate.matching.entity.MatchRequest;
import cloud.bamsongi.albammate.matching.repository.MatchBlockRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalMemberRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalRepository;
import cloud.bamsongi.albammate.matching.repository.MatchRequestRepository;

@Service
public class MatchProposalExecutor {

	private static final int CANDIDATE_BATCH_SIZE = 100;

	private final MatchRequestRepository requestRepository;
	private final MatchProposalRepository proposalRepository;
	private final MatchProposalMemberRepository proposalMemberRepository;
	private final MatchBlockRepository blockRepository;
	private final JdbcTemplate jdbcTemplate;

	public MatchProposalExecutor(
		MatchRequestRepository requestRepository,
		MatchProposalRepository proposalRepository,
		MatchProposalMemberRepository proposalMemberRepository,
		MatchBlockRepository blockRepository,
		JdbcTemplate jdbcTemplate) {
		this.requestRepository = requestRepository;
		this.proposalRepository = proposalRepository;
		this.proposalMemberRepository = proposalMemberRepository;
		this.blockRepository = blockRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void claimAvailableCandidates() {
		List<MatchRequest> lockedWaitingRequests = requestRepository
			.findWaitingForUpdateSkipLocked(CANDIDATE_BATCH_SIZE);
		if (lockedWaitingRequests.isEmpty()) {
			return;
		}
		MatchRequest oldestAnchor = lockedWaitingRequests.get(0);
		Candidate candidate = findCandidate(oldestAnchor, lockedWaitingRequests);
		if (candidate != null) {
			claim(candidate);
		}
	}

	private Candidate findCandidate(MatchRequest anchor, List<MatchRequest> waitingRequests) {
		for (int targetPartySize = anchor.getMinPartySize(); targetPartySize <= anchor
			.getMaxPartySize(); targetPartySize++) {
			List<MatchRequest> selected = new ArrayList<>();
			selected.add(anchor);
			for (MatchRequest candidate : waitingRequests) {
				if (candidate.getId().equals(anchor.getId())) {
					continue;
				}
				if (!acceptsPartySize(candidate, targetPartySize)) {
					continue;
				}
				if (!isCompatible(selected, candidate)) {
					continue;
				}
				selected.add(candidate);
				if (selected.size() == targetPartySize) {
					if (acceptsPartySize(selected, targetPartySize)) {
						return new Candidate(selected, intersectionMinimum(selected));
					}
					break;
				}
			}
		}
		return null;
	}

	private boolean isCompatible(List<MatchRequest> selected, MatchRequest candidate) {
		for (MatchRequest selectedRequest : selected) {
			if (blockRepository.existsBlockBetweenUsers(selectedRequest.getUserId(), candidate.getUserId())) {
				return false;
			}
		}
		return true;
	}

	private boolean acceptsPartySize(List<MatchRequest> selected, int targetPartySize) {
		return intersectionMinimum(selected) <= targetPartySize && targetPartySize <= intersectionMaximum(selected);
	}

	private boolean acceptsPartySize(MatchRequest request, int targetPartySize) {
		return request.getMinPartySize() <= targetPartySize && targetPartySize <= request.getMaxPartySize();
	}

	private int intersectionMinimum(List<MatchRequest> selected) {
		return selected.stream().mapToInt(MatchRequest::getMinPartySize).max().orElseThrow();
	}

	private int intersectionMaximum(List<MatchRequest> selected) {
		return selected.stream().mapToInt(MatchRequest::getMaxPartySize).min().orElseThrow();
	}

	private void claim(Candidate candidate) {
		Instant operationTime = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class).toInstant();
		MatchProposal proposal = proposalRepository.save(MatchProposal.create(
			candidate.partySize(), operationTime.plusSeconds(30)));
		for (MatchRequest request : candidate.requests()) {
			request.markProposed(operationTime);
			proposalMemberRepository.save(MatchProposalMember.create(
				proposal.getId(), request.getId(), request.getUserId(), MatchProposalResponseStatus.PENDING, null));
		}
	}

	private record Candidate(List<MatchRequest> requests, int partySize) {
	}
}
