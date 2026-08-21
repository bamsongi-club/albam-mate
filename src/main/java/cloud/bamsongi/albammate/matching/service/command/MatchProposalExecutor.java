package cloud.bamsongi.albammate.matching.service.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;
import jakarta.persistence.EntityManager;

@Service
public class MatchProposalExecutor {

	private static final int CANDIDATE_PAGE_SIZE = 100;

	private final MatchRequestRepository requestRepository;
	private final MatchProposalRepository proposalRepository;
	private final MatchProposalMemberRepository proposalMemberRepository;
	private final MatchBlockRepository blockRepository;
	private final UserRowLockPort userRowLockPort;
	private final JdbcTemplate jdbcTemplate;
	private final EntityManager entityManager;

	public MatchProposalExecutor(
		MatchRequestRepository requestRepository,
		MatchProposalRepository proposalRepository,
		MatchProposalMemberRepository proposalMemberRepository,
		MatchBlockRepository blockRepository,
		UserRowLockPort userRowLockPort,
		JdbcTemplate jdbcTemplate,
		EntityManager entityManager) {
		this.requestRepository = requestRepository;
		this.proposalRepository = proposalRepository;
		this.proposalMemberRepository = proposalMemberRepository;
		this.blockRepository = blockRepository;
		this.userRowLockPort = userRowLockPort;
		this.jdbcTemplate = jdbcTemplate;
		this.entityManager = entityManager;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void claimAvailableCandidates() {
		MatchRequest oldestAnchor = requestRepository.findOldestWaitingForUpdateSkipLocked().stream()
			.findFirst()
			.orElse(null);
		while (oldestAnchor != null) {
			if (requestRepository.tryLockPrioritySince(oldestAnchor.getId())) {
				List<MatchRequest> samePriorityRequests = requestRepository
					.findWaitingWithSamePrioritySinceForUpdate(oldestAnchor.getId());
				if (!samePriorityRequests.isEmpty()) {
					RequestSnapshot anchor = RequestSnapshot.from(samePriorityRequests.get(0));
					entityManager.clear();
					CandidateSnapshot candidateSnapshot = findCandidate(anchor);
					entityManager.clear();
					Candidate candidate = loadCandidate(candidateSnapshot);
					if (candidate != null && lockUsersAndRecheckBlocks(candidate)) {
						claim(candidate);
					}
					return;
				}
			}
			oldestAnchor = requestRepository.findOldestWaitingAfterPriorityForUpdateSkipLocked(
				oldestAnchor.getPrioritySince()).stream().findFirst().orElse(null);
		}
	}

	private CandidateSnapshot findCandidate(RequestSnapshot anchor) {
		for (int targetPartySize = anchor.minPartySize(); targetPartySize <= anchor
			.maxPartySize(); targetPartySize++) {
			List<RequestSnapshot> selected = new ArrayList<>();
			selected.add(anchor);
			if (canConfirmCandidate(selected, targetPartySize)) {
				return new CandidateSnapshot(selected, targetPartySize);
			}
			RequestCursor cursor = RequestCursor.from(anchor);
			while (true) {
				List<MatchRequest> candidatePage = requestRepository.findWaitingAfterForUpdateSkipLocked(
					cursor.prioritySince(), cursor.requestId(), CANDIDATE_PAGE_SIZE);
				if (candidatePage.isEmpty()) {
					break;
				}
				for (MatchRequest candidateRequest : candidatePage) {
					RequestSnapshot candidate = RequestSnapshot.from(candidateRequest);
					cursor = RequestCursor.from(candidate);
					if (!acceptsPartySize(candidate, targetPartySize)) {
						continue;
					}
					if (!isCompatible(selected, candidate)) {
						continue;
					}
					selected.add(candidate);
					if (selected.size() == targetPartySize) {
						if (canConfirmCandidate(selected, targetPartySize)) {
							return new CandidateSnapshot(selected, targetPartySize);
						}
						break;
					}
				}
				entityManager.clear();
			}
		}
		return null;
	}

	private Candidate loadCandidate(CandidateSnapshot candidateSnapshot) {
		if (candidateSnapshot == null) {
			return null;
		}
		Map<Long, MatchRequest> requestsById = requestRepository.findAllById(
			candidateSnapshot.requests().stream().map(RequestSnapshot::id).toList()).stream()
			.collect(java.util.stream.Collectors.toMap(MatchRequest::getId, request -> request));
		List<MatchRequest> requests = candidateSnapshot.requests().stream()
			.map(snapshot -> requestsById.get(snapshot.id()))
			.toList();
		if (requests.stream().anyMatch(java.util.Objects::isNull)) {
			return null;
		}
		return new Candidate(requests, candidateSnapshot.partySize());
	}

	private boolean canConfirmCandidate(List<RequestSnapshot> selected, int targetPartySize) {
		return selected.size() == targetPartySize
			&& intersectionMinimum(selected) == targetPartySize
			&& acceptsPartySize(selected, targetPartySize);
	}

	private boolean lockUsersAndRecheckBlocks(Candidate candidate) {
		userRowLockPort.lockExistingUsersInAscendingOrder(
			candidate.requests().stream().map(MatchRequest::getUserId).toList());
		for (MatchRequest request : candidate.requests()) {
			if (!isCompatibleAfterUserLock(candidate.requests(), request)) {
				return false;
			}
		}
		return true;
	}

	private boolean isCompatible(List<RequestSnapshot> selected, RequestSnapshot candidate) {
		for (RequestSnapshot selectedRequest : selected) {
			if (selectedRequest.id() == candidate.id()) {
				continue;
			}
			if (blockRepository.existsBlockBetweenUsers(selectedRequest.userId(), candidate.userId())) {
				return false;
			}
		}
		return true;
	}

	private boolean isCompatibleAfterUserLock(List<MatchRequest> selected, MatchRequest candidate) {
		for (MatchRequest selectedRequest : selected) {
			if (selectedRequest.getId().equals(candidate.getId())) {
				continue;
			}
			if (blockRepository.existsBlockBetweenUsers(selectedRequest.getUserId(), candidate.getUserId())) {
				return false;
			}
		}
		return true;
	}

	private boolean acceptsPartySize(List<RequestSnapshot> selected, int targetPartySize) {
		return intersectionMinimum(selected) <= targetPartySize && targetPartySize <= intersectionMaximum(selected);
	}

	private boolean acceptsPartySize(RequestSnapshot request, int targetPartySize) {
		return request.minPartySize() <= targetPartySize && targetPartySize <= request.maxPartySize();
	}

	private int intersectionMinimum(List<RequestSnapshot> selected) {
		return selected.stream().mapToInt(RequestSnapshot::minPartySize).max().orElseThrow();
	}

	private int intersectionMaximum(List<RequestSnapshot> selected) {
		return selected.stream().mapToInt(RequestSnapshot::maxPartySize).min().orElseThrow();
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

	private record CandidateSnapshot(List<RequestSnapshot> requests, int partySize) {
	}

	private record RequestCursor(Instant prioritySince, long requestId) {

		private static RequestCursor from(RequestSnapshot request) {
			return new RequestCursor(request.prioritySince(), request.id());
		}
	}

	private record RequestSnapshot(long id, long userId, int minPartySize, int maxPartySize, Instant prioritySince) {

		private static RequestSnapshot from(MatchRequest request) {
			return new RequestSnapshot(request.getId(), request.getUserId(), request.getMinPartySize(),
				request.getMaxPartySize(), request.getPrioritySince());
		}
	}
}
