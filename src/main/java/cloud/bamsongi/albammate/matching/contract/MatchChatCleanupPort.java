package cloud.bamsongi.albammate.matching.contract;

/** MATCH 채팅 메시지와 방을 정해진 순서로 정리하는 chat 모듈 공개 port다. */
public interface MatchChatCleanupPort {

	void cleanup(long partyId);
}
