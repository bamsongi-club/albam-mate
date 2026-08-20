package cloud.bamsongi.albammate.matching.service.command;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.entity.MatchParty;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.repository.MatchPartyRepository;

@Service
public class MatchPartyLeaveExecutor {

	private final MatchPartyRepository partyRepository;
	private final MatchPartyParticipantRepository participantRepository;
	private final JdbcTemplate jdbcTemplate;

	public MatchPartyLeaveExecutor(
		MatchPartyRepository partyRepository,
		MatchPartyParticipantRepository participantRepository,
		JdbcTemplate jdbcTemplate) {
		this.partyRepository = partyRepository;
		this.participantRepository = participantRepository;
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	public CurrentMatchStateResponse leave(long partyId, long userId) {
		MatchPartyParticipant participant = participantRepository.findParticipantByPartyIdAndUserId(partyId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
		MatchParty party = partyRepository.findByIdForUpdate(partyId)
			.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
		if (party.getStatus() == MatchPartyStatus.PREPARING) {
			throw new BusinessException(ErrorCode.MATCH_PARTY_LEAVE_NOT_AVAILABLE);
		}
		Instant operationTime = jdbcTemplate.queryForObject("select clock_timestamp()", java.sql.Timestamp.class).toInstant();
		if (party.isClosingDue(operationTime)) {
			party.close(operationTime);
			return CurrentMatchStateResponse.empty(operationTime);
		}
		if (participant.getLeftAt() != null || party.getStatus() == MatchPartyStatus.CLOSED) {
			return CurrentMatchStateResponse.empty(operationTime);
		}
		participant.leave(operationTime);
		if (participantRepository.countByIdPartyIdAndLeftAtIsNull(partyId) == 0) {
			party.close(operationTime);
		}
		return CurrentMatchStateResponse.empty(operationTime);
	}
}
