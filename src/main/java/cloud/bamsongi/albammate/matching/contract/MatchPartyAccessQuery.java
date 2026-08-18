package cloud.bamsongi.albammate.matching.contract;

/** chat이 현재 MATCH Party 접근을 확인할 때 사용하는 matching 공개 조회 계약이다. */
public interface MatchPartyAccessQuery {

	MatchPartyChatAccess evaluateChatAccess(long currentUserId, long partyId);
}
