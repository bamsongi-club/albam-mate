package cloud.bamsongi.albammate.matching.contract;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * chat이 MATCH Party에서 사용자의 opaque participant reference를 조회할 때 사용하는 matching 공개 계약이다.
 * 반환값은 해당 Party의 현재 또는 과거(이탈 포함) 참가자에게 저장된 participant_ref이며, matching 내부 UUID 표현은
 * 외부로 노출하지 않고 문자열로만 공개한다.
 */
public interface MatchPartyParticipantRefQuery {

	Optional<String> findParticipantRef(long partyId, long userId);

	Map<Long, String> findParticipantRefs(long partyId, Collection<Long> userIds);
}
