package cloud.bamsongi.albammate.matching.service.query;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import lombok.RequiredArgsConstructor;

/**
 * chat이 소비하는 {@link MatchPartyParticipantRefQuery}의 matching 구현체다. participant_ref는 해당 Party의
 * 현재 또는 과거(이탈 포함) 참가자에게만 저장되어 있으므로, 참가한 적 없는 조합은 예외 없이 결과에서 제외한다.
 */
@Service
@RequiredArgsConstructor
public class MatchPartyParticipantRefQueryService implements MatchPartyParticipantRefQuery {

	private final MatchPartyParticipantRepository participantRepository;

	@Override
	@Transactional(readOnly = true)
	public Optional<String> findParticipantRef(long partyId, long userId) {
		return participantRepository.findParticipantByPartyIdAndUserId(partyId, userId)
			.map(participant -> participant.getParticipantRef().toString());
	}

	@Override
	@Transactional(readOnly = true)
	public Map<Long, String> findParticipantRefs(long partyId, Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return participantRepository.findParticipantsByPartyIdAndUserIds(partyId, userIds).stream()
			.collect(Collectors.toMap(
				MatchPartyParticipantRefQueryService::userId,
				participant -> participant.getParticipantRef().toString()));
	}

	private static Long userId(MatchPartyParticipant participant) {
		return participant.getId().getUserId();
	}
}
