package cloud.bamsongi.albammate.matching.service.query;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.MatchProposalStatus;
import cloud.bamsongi.albammate.matching.MatchRequestStatus;
import cloud.bamsongi.albammate.matching.contract.MatchChatSystemMessagePort;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.dto.MatchPartyMember;
import cloud.bamsongi.albammate.matching.dto.MatchProposalMemberPreview;
import cloud.bamsongi.albammate.matching.dto.MatchProposalSummary;
import cloud.bamsongi.albammate.matching.entity.MatchParty;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.entity.MatchProposal;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMember;
import cloud.bamsongi.albammate.matching.entity.MatchRequest;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalMemberRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalRepository;
import cloud.bamsongi.albammate.matching.repository.MatchRequestRepository;
import cloud.bamsongi.albammate.user.contract.UserPublicProfile;
import cloud.bamsongi.albammate.user.contract.UserQuery;

@Service
public class MatchCurrentStateReadService {

	private final MatchRequestRepository matchRequestRepository;
	private final MatchProposalRepository proposalRepository;
	private final MatchProposalMemberRepository proposalMemberRepository;
	private final MatchPartyRepository partyRepository;
	private final MatchPartyParticipantRepository participantRepository;
	private final MatchChatSystemMessagePort matchChatSystemMessagePort;
	private final UserQuery userQuery;
	private final JdbcTemplate jdbcTemplate;

	public MatchCurrentStateReadService(
		MatchRequestRepository matchRequestRepository,
		MatchProposalRepository proposalRepository,
		MatchProposalMemberRepository proposalMemberRepository,
		MatchPartyRepository partyRepository,
		MatchPartyParticipantRepository participantRepository,
		MatchChatSystemMessagePort matchChatSystemMessagePort,
		UserQuery userQuery,
		JdbcTemplate jdbcTemplate) {
		this.matchRequestRepository = matchRequestRepository;
		this.proposalRepository = proposalRepository;
		this.proposalMemberRepository = proposalMemberRepository;
		this.partyRepository = partyRepository;
		this.participantRepository = participantRepository;
		this.matchChatSystemMessagePort = matchChatSystemMessagePort;
		this.userQuery = userQuery;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ, readOnly = true)
	public CurrentMatchStateResponse read(long userId) {
		Timestamp transactionTimestamp = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class);
		Instant operationTime = transactionTimestamp.toInstant();
		Optional<MatchRequest> request = matchRequestRepository.findCurrentByUserId(userId);
		Optional<MatchParty> party = partyRepository.findCurrentByUserId(userId);
		if (request.isPresent() && party.isPresent()) {
			throw new BusinessException(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE);
		}
		if (party.isPresent()) {
			ensurePartyIsStable(operationTime, party.get());
			return partyState(operationTime, party.get(), userId);
		}
		if (request.isEmpty()) {
			return CurrentMatchStateResponse.empty(operationTime);
		}
		if (request.get().getStatus() == MatchRequestStatus.PAUSED) {
			return CurrentMatchStateResponse.paused(operationTime, request.get());
		}
		if (request.get().getStatus() == MatchRequestStatus.PROPOSED) {
			return proposed(operationTime, request.get());
		}
		return CurrentMatchStateResponse.waiting(operationTime, request.get());
	}

	private CurrentMatchStateResponse partyState(Instant operationTime, MatchParty party, long currentUserId) {
		if (party.getStatus() == MatchPartyStatus.PREPARING) {
			return CurrentMatchStateResponse.preparing(operationTime, party);
		}
		List<MatchPartyParticipant> participants = participantRepository
			.findAllByIdPartyIdAndLeftAtIsNullOrderByCreatedAtAsc(party.getId());
		Map<Long, UserPublicProfile> profiles = userQuery.findPublicProfilesByIds(
			participants.stream().map(participant -> participant.getId().getUserId()).toList());
		List<MatchPartyMember> members = participants.stream()
			.map(participant -> toPartyMember(participant, profiles, currentUserId))
			.toList();
		return CurrentMatchStateResponse.active(operationTime, party, members);
	}

	private void ensurePartyIsStable(Instant operationTime, MatchParty party) {
		if (party.getStatus() == MatchPartyStatus.PREPARING
			&& !operationTime.isBefore(party.getPreparingStartedAt().plusSeconds(300))) {
			throw notStable();
		}
		if (party.isClosingDue(operationTime)) {
			throw notStable();
		}
		if (party.isCloseNoticeDue(operationTime)
			&& !matchChatSystemMessagePort.hasPersistedEvent(party.getId(), "CLOSES_IN_ONE_HOUR")) {
			throw notStable();
		}
	}

	private MatchPartyMember toPartyMember(
		MatchPartyParticipant participant, Map<Long, UserPublicProfile> profiles, long currentUserId) {
		UserPublicProfile profile = profiles.get(participant.getId().getUserId());
		return new MatchPartyMember(
			participant.getParticipantRef(),
			profile == null ? "" : profile.nickname(),
			profile == null ? null : profile.profileImageUrl(),
			participant.getId().getUserId() == currentUserId);
	}

	private CurrentMatchStateResponse proposed(Instant operationTime, MatchRequest request) {
		MatchProposalMember currentMember = proposalMemberRepository.findByMatchRequestId(request.getId())
			.orElseThrow();
		MatchProposal proposal = proposalRepository.findById(currentMember.getId().getProposalId()).orElseThrow();
		if (proposal.getStatus() == MatchProposalStatus.OPEN
			&& !operationTime.isBefore(proposal.getRespondBy())) {
			throw notStable();
		}
		List<MatchProposalMember> members = proposalMemberRepository.findAllByProposalId(proposal.getId());
		Map<Long, UserPublicProfile> profiles = userQuery.findPublicProfilesByIds(
			members.stream().map(MatchProposalMember::getUserId).toList());
		List<MatchProposalMemberPreview> previews = members.stream()
			.map(member -> new MatchProposalMemberPreview(profileImageUrl(profiles, member.getUserId())))
			.toList();
		MatchProposalSummary proposalSummary = new MatchProposalSummary(
			proposal.getId(), proposal.getPartySize(), previews, proposal.getRespondBy(),
			currentMember.getResponseStatus());
		return CurrentMatchStateResponse.proposed(operationTime, request, proposalSummary);
	}

	private String profileImageUrl(Map<Long, UserPublicProfile> profiles, long userId) {
		UserPublicProfile profile = profiles.get(userId);
		return profile == null ? null : profile.profileImageUrl();
	}

	private BusinessException notStable() {
		return new BusinessException(ErrorCode.MATCH_CURRENT_STATE_NOT_STABLE);
	}
}
