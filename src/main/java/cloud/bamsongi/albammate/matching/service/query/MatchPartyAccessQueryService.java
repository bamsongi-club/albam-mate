package cloud.bamsongi.albammate.matching.service.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchPartyAccessQueryService implements MatchPartyAccessQuery {

	private final MatchPartyParticipantRepository participantRepository;

	@Override
	@Transactional(readOnly = true)
	public boolean hasActiveAccess(long currentUserId, long partyId) {
		return participantRepository.existsCurrentParticipantForPartyStatus(
			partyId,
			currentUserId,
			MatchPartyStatus.ACTIVE);
	}
}
