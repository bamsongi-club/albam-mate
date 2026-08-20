package cloud.bamsongi.albammate.matching.service.command;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.MatchIdempotencyOperation;
import cloud.bamsongi.albammate.matching.MatchProposalResponseAction;
import cloud.bamsongi.albammate.matching.MatchProposalResponseStatus;
import cloud.bamsongi.albammate.matching.MatchProposalStatus;
import cloud.bamsongi.albammate.matching.entity.MatchIdempotencyRecord;
import cloud.bamsongi.albammate.matching.entity.MatchParty;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.entity.MatchProposal;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMember;
import cloud.bamsongi.albammate.matching.entity.MatchRequest;
import cloud.bamsongi.albammate.matching.repository.MatchIdempotencyRecordRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalMemberRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalRepository;
import cloud.bamsongi.albammate.matching.repository.MatchRequestRepository;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;

@Service
public class MatchProposalResponseExecutor {

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void expireIfDue(long proposalId) {
		MatchProposal proposal = proposalRepository.findByIdForUpdate(proposalId).orElse(null);
		if (proposal == null || proposal.getStatus() != MatchProposalStatus.OPEN) {
			return;
		}
		List<MatchProposalMember> members = proposalMemberRepository.findAllByProposalId(proposalId);
		userRowLockPort
			.lockExistingUsersInAscendingOrder(members.stream().map(MatchProposalMember::getUserId).toList());
		Instant operationTime = currentDatabaseTime();
		if (proposal.getRespondBy().isAfter(operationTime)) {
			return;
		}
		expire(proposal, members, operationTime);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean cancelOpenProposalForRequest(long userId, long requestId) {
		MatchProposalMember member = proposalMemberRepository.findByMatchRequestId(requestId).orElse(null);
		if (member == null || member.getUserId() != userId) {
			return false;
		}
		MatchProposal proposal = proposalRepository.findByIdForUpdate(member.getId().getProposalId()).orElse(null);
		if (proposal == null || proposal.getStatus() != MatchProposalStatus.OPEN) {
			return false;
		}
		List<MatchProposalMember> members = proposalMemberRepository.findAllByProposalId(proposal.getId());
		MatchProposalMember currentMember = findCurrentMember(members, userId);
		if (currentMember == null) {
			return false;
		}
		userRowLockPort
			.lockExistingUsersInAscendingOrder(members.stream().map(MatchProposalMember::getUserId).toList());
		Instant operationTime = currentDatabaseTime();
		if (!proposal.getRespondBy().isAfter(operationTime)) {
			expire(proposal, members, operationTime);
			return false;
		}
		proposal.cancel(operationTime);
		currentMember.cancel(operationTime);
		for (MatchProposalMember proposalMember : members) {
			MatchRequest request = requestRepository.findById(proposalMember.getId().getMatchRequestId()).orElseThrow();
			if (proposalMember.getId().equals(currentMember.getId())) {
				request.cancel(operationTime);
			} else {
				request.resumeWaiting();
			}
		}
		return true;
	}

	private final MatchProposalRepository proposalRepository;
	private final MatchProposalMemberRepository proposalMemberRepository;
	private final MatchRequestRepository requestRepository;
	private final MatchPartyRepository partyRepository;
	private final MatchPartyParticipantRepository participantRepository;
	private final MatchIdempotencyRecordRepository idempotencyRecordRepository;
	private final UserRowLockPort userRowLockPort;
	private final JdbcTemplate jdbcTemplate;

	public MatchProposalResponseExecutor(
		MatchProposalRepository proposalRepository,
		MatchProposalMemberRepository proposalMemberRepository,
		MatchRequestRepository requestRepository,
		MatchPartyRepository partyRepository,
		MatchPartyParticipantRepository participantRepository,
		MatchIdempotencyRecordRepository idempotencyRecordRepository,
		UserRowLockPort userRowLockPort,
		JdbcTemplate jdbcTemplate) {
		this.proposalRepository = proposalRepository;
		this.proposalMemberRepository = proposalMemberRepository;
		this.requestRepository = requestRepository;
		this.partyRepository = partyRepository;
		this.participantRepository = participantRepository;
		this.idempotencyRecordRepository = idempotencyRecordRepository;
		this.userRowLockPort = userRowLockPort;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void respond(long userId, long proposalId, MatchProposalResponseAction action, String idempotencyKey) {
		String fingerprint = proposalId + ":" + action.name();
		Instant precheckTime = currentDatabaseTime();
		MatchIdempotencyRecord activeRecord = idempotencyRecordRepository
			.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
			.orElse(null);
		if (activeRecord != null && !activeRecord.isExpiredAt(precheckTime)) {
			if (activeRecord.hasSameMeaning(MatchIdempotencyOperation.MATCH_PROPOSAL_RESPONSE, fingerprint)) {
				return;
			}
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		}
		MatchProposal proposal = proposalRepository.findByIdForUpdate(proposalId)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE));
		List<MatchProposalMember> members = proposalMemberRepository.findAllByProposalId(proposalId);
		userRowLockPort
			.lockExistingUsersInAscendingOrder(members.stream().map(MatchProposalMember::getUserId).toList());
		Instant operationTime = currentDatabaseTime();
		MatchIdempotencyRecord record = idempotencyRecordRepository
			.findByUserIdAndIdempotencyKeyForUpdate(userId, idempotencyKey)
			.orElse(null);
		if (record != null && !record.isExpiredAt(operationTime)) {
			if (record.hasSameMeaning(MatchIdempotencyOperation.MATCH_PROPOSAL_RESPONSE, fingerprint)) {
				return;
			}
			throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
		}
		if (proposal.getStatus() != MatchProposalStatus.OPEN || !proposal.getRespondBy().isAfter(operationTime)) {
			throw new BusinessException(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE);
		}
		MatchProposalMember currentMember = findCurrentMember(members, userId);
		if (currentMember == null) {
			throw new BusinessException(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE);
		}
		if (currentMember.getResponseStatus() != MatchProposalResponseStatus.PENDING) {
			throw new BusinessException(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE);
		}
		if (action == MatchProposalResponseAction.REQUEUE) {
			declineForRequeue(proposal, members, currentMember, operationTime);
			storeIdempotency(record, userId, idempotencyKey, fingerprint, proposalId, operationTime);
			return;
		}
		if (action == MatchProposalResponseAction.CANCEL) {
			proposal.cancel(operationTime);
			currentMember.cancel(operationTime);
			for (MatchProposalMember member : members) {
				MatchRequest request = requestRepository.findById(member.getId().getMatchRequestId()).orElseThrow();
				if (member.getId().equals(currentMember.getId())) {
					request.cancel(operationTime);
				} else {
					request.resumeWaiting();
				}
			}
			storeIdempotency(record, userId, idempotencyKey, fingerprint, proposalId, operationTime);
			return;
		}
		if (action != MatchProposalResponseAction.ACCEPT) {
			throw new BusinessException(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE);
		}
		currentMember.accept(operationTime);
		if (members.stream().allMatch(member -> member.getResponseStatus() == MatchProposalResponseStatus.ACCEPTED)) {
			confirm(proposal, members, operationTime);
		}
		storeIdempotency(record, userId, idempotencyKey, fingerprint, proposalId, operationTime);
	}

	private void storeIdempotency(
		MatchIdempotencyRecord record,
		long userId,
		String idempotencyKey,
		String fingerprint,
		long proposalId,
		Instant operationTime) {
		if (record == null) {
			idempotencyRecordRepository.save(MatchIdempotencyRecord.create(
				userId, idempotencyKey, MatchIdempotencyOperation.MATCH_PROPOSAL_RESPONSE,
				fingerprint, "MATCH_PROPOSAL", proposalId, "RESPONDED", operationTime));
			return;
		}
		record.replace(MatchIdempotencyOperation.MATCH_PROPOSAL_RESPONSE,
			fingerprint, "MATCH_PROPOSAL", proposalId, "RESPONDED", operationTime);
	}

	private void declineForRequeue(
		MatchProposal proposal,
		List<MatchProposalMember> members,
		MatchProposalMember requeuedMember,
		Instant operationTime) {
		proposal.decline(operationTime);
		requeuedMember.requeue(operationTime);
		for (MatchProposalMember member : members) {
			MatchRequest request = requestRepository.findById(member.getId().getMatchRequestId()).orElseThrow();
			if (member.getId().equals(requeuedMember.getId())) {
				request.startNewWaitingAttempt(operationTime);
			} else {
				request.resumeWaiting();
			}
		}
	}

	private void expire(MatchProposal proposal, List<MatchProposalMember> members, Instant operationTime) {
		proposal.expire(operationTime);
		for (MatchProposalMember member : members) {
			MatchRequest request = requestRepository.findById(member.getId().getMatchRequestId()).orElseThrow();
			if (member.getResponseStatus() == MatchProposalResponseStatus.ACCEPTED) {
				request.resumeWaiting();
			} else {
				member.expire();
				request.pause();
			}
		}
	}

	private Instant currentDatabaseTime() {
		return jdbcTemplate.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
	}

	private MatchProposalMember findCurrentMember(List<MatchProposalMember> members, long userId) {
		return members.stream()
			.filter(member -> member.getUserId() == userId)
			.findFirst()
			.orElse(null);
	}

	private void confirm(MatchProposal proposal, List<MatchProposalMember> members, Instant operationTime) {
		proposal.confirm(operationTime);
		MatchParty party = partyRepository.save(MatchParty.create(proposal.getId(), operationTime));
		for (MatchProposalMember member : members) {
			MatchRequest request = requestRepository.findById(member.getId().getMatchRequestId()).orElseThrow();
			request.markMatched(operationTime);
			participantRepository.save(MatchPartyParticipant.create(
				party.getId(), member.getUserId(), UUID.randomUUID(), operationTime));
		}
	}
}
