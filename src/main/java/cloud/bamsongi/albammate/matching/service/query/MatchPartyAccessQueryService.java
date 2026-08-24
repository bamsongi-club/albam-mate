package cloud.bamsongi.albammate.matching.service.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.MatchPartyStatus;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchPartyAccessQueryService implements MatchPartyAccessQuery {

	private final MatchPartyParticipantRepository participantRepository;

	@Override
	@Transactional(readOnly = true)
	public MatchPartyChatAccess evaluateChatAccess(long currentUserId, long partyId) {
		return participantRepository.findCurrentParticipantPartyStatus(partyId, currentUserId)
			.map(this::toChatAccess)
			.orElse(MatchPartyChatAccess.FORBIDDEN);
	}

	private MatchPartyChatAccess toChatAccess(MatchPartyStatus partyStatus) {
		return switch (partyStatus) {
			case ACTIVE -> MatchPartyChatAccess.ALLOWED;
			case PREPARING -> MatchPartyChatAccess.NOT_ACTIVE;
			case CLOSED -> MatchPartyChatAccess.FORBIDDEN;
		};
	}
}
