package cloud.bamsongi.albammate.matching.contract;

/** Party lifecycle 시스템 메시지를 같은 트랜잭션에서 저장하는 chat 모듈 공개 port다. */
public interface MatchChatSystemMessagePort {

	void record(long partyId, String eventKey);
}
