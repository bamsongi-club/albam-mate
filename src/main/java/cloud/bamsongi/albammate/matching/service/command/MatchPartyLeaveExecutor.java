package cloud.bamsongi.albammate.matching.service.command;

import java.time.Clock;
import java.time.Instant;

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
	private final Clock clock;

	public MatchPartyLeaveExecutor(
		MatchPartyRepository partyRepository,
		MatchPartyParticipantRepository participantRepository,
		Clock clock) {
		this.partyRepository = partyRepository;
		this.participantRepository = participantRepository;
		this.clock = clock;
	}

	@Transactional
	public CurrentMatchStateResponse leave(long partyId, long userId) {
		MatchParty party = partyRepository.findByIdForUpdate(partyId)
			.orElseThrow(() -> new BusinessException(ErrorCode.MATCH_PARTY_NOT_FOUND));
		MatchPartyParticipant participant = participantRepository.findParticipantByPartyIdAndUserId(partyId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
		if (party.getStatus() == MatchPartyStatus.PREPARING) {
			throw new BusinessException(ErrorCode.MATCH_PARTY_LEAVE_NOT_AVAILABLE);
		}
		Instant operationTime = Instant.now(clock);
		if (participant.getLeftAt() != null) {
			return CurrentMatchStateResponse.empty(operationTime);
		}
		if (party.getStatus() == MatchPartyStatus.CLOSED) {
			return CurrentMatchStateResponse.empty(operationTime);
		}
		participant.leave(operationTime);
		if (participantRepository.countByIdPartyIdAndLeftAtIsNull(partyId) == 0) {
			party.close(operationTime);
		}
		return CurrentMatchStateResponse.empty(operationTime);
	}
}
