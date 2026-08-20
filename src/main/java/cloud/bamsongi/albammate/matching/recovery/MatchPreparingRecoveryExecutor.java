package cloud.bamsongi.albammate.matching.recovery;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.contract.MatchChatCleanupPort;
import cloud.bamsongi.albammate.matching.contract.MatchChatProvisionPort;
import cloud.bamsongi.albammate.matching.contract.MatchChatSystemMessagePort;
import cloud.bamsongi.albammate.matching.entity.MatchProposalMember;
import cloud.bamsongi.albammate.matching.entity.MatchRequest;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;
import cloud.bamsongi.albammate.matching.repository.MatchProposalMemberRepository;
import cloud.bamsongi.albammate.matching.repository.MatchRequestRepository;

@Service
public class MatchPreparingRecoveryExecutor {

	private static final long PREPARING_SECONDS = 300;

	private final MatchPartyRepository partyRepository;
	private final MatchChatProvisionPort chatProvisionPort;
	private final MatchChatCleanupPort chatCleanupPort;
	private final MatchChatSystemMessagePort chatSystemMessagePort;
	private final MatchProposalMemberRepository proposalMemberRepository;
	private final MatchRequestRepository requestRepository;
	private final JdbcTemplate jdbcTemplate;

	public MatchPreparingRecoveryExecutor(
		MatchPartyRepository partyRepository,
		MatchChatProvisionPort chatProvisionPort,
		MatchChatCleanupPort chatCleanupPort,
		MatchChatSystemMessagePort chatSystemMessagePort,
		MatchProposalMemberRepository proposalMemberRepository,
		MatchRequestRepository requestRepository,
		JdbcTemplate jdbcTemplate) {
		this.partyRepository = partyRepository;
		this.chatProvisionPort = chatProvisionPort;
		this.chatCleanupPort = chatCleanupPort;
		this.chatSystemMessagePort = chatSystemMessagePort;
		this.proposalMemberRepository = proposalMemberRepository;
		this.requestRepository = requestRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recover(long partyId) {
		var party = partyRepository.findByIdForUpdate(partyId).orElse(null);
		if (party == null || party.getStatus() != MatchPartyStatus.PREPARING) {
			return;
		}
		Instant operationTime = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class).toInstant();
		Instant preparingDeadline = party.getPreparingStartedAt().plusSeconds(PREPARING_SECONDS);
		if (!operationTime.isBefore(preparingDeadline)) {
			cleanUpFailedPreparingParty(party.getId(), party.getProposalId());
			return;
		}
		chatProvisionPort.provision(party.getId());
		party.activate(operationTime);
		chatSystemMessagePort.record(party.getId(), "CHAT_OPENED");
	}

	private void cleanUpFailedPreparingParty(long partyId, Long proposalId) {
		chatCleanupPort.cleanup(partyId);
		if (proposalId != null) {
			for (MatchProposalMember member : proposalMemberRepository.findAllByProposalId(proposalId)) {
				MatchRequest request = requestRepository.findById(member.getId().getMatchRequestId()).orElseThrow();
				request.resumeWaiting();
			}
		}
		partyRepository.deleteById(partyId);
	}
}
