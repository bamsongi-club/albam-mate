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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

@Service
public class MatchPartyLeaveExecutor {

	private final MatchPartyRepository partyRepository;
	private final MatchPartyParticipantRepository participantRepository;
	private final JdbcTemplate jdbcTemplate;
	private final EntityManager entityManager;

	public MatchPartyLeaveExecutor(
		MatchPartyRepository partyRepository,
		MatchPartyParticipantRepository participantRepository,
		JdbcTemplate jdbcTemplate,
		EntityManager entityManager) {
		this.partyRepository = partyRepository;
		this.participantRepository = participantRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.entityManager = entityManager;
	}

	@Transactional
	public CurrentMatchStateResponse leave(long partyId, long userId) {
		MatchPartyParticipant participant = participantRepository.findParticipantByPartyIdAndUserId(partyId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
		MatchParty party = partyRepository.findByIdForUpdate(partyId)
			.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
		entityManager.refresh(participant, LockModeType.PESSIMISTIC_WRITE);
		if (party.getStatus() == MatchPartyStatus.PREPARING) {
			throw new BusinessException(ErrorCode.MATCH_PARTY_LEAVE_NOT_AVAILABLE);
		}
		Instant operationTime = jdbcTemplate.queryForObject("select clock_timestamp()", java.sql.Timestamp.class)
			.toInstant();
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
