package cloud.bamsongi.albammate.matching.contract;

/** MATCH Party별 채팅방을 준비하는 chat 모듈 공개 port다. */
public interface MatchChatProvisionPort {

	void provision(long partyId);
}
