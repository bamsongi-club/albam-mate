package cloud.bamsongi.albammate.matching.contract;

/** Party lifecycle 시스템 메시지를 같은 트랜잭션에서 저장하는 chat 모듈 공개 port다. */
public interface MatchChatSystemMessagePort {

	void record(long partyId, String eventKey);

	/** 사용자 전달·열람과 무관하게 system event가 DB에 저장됐는지만 확인한다. */
	boolean hasPersistedEvent(long partyId, String eventKey);
}
