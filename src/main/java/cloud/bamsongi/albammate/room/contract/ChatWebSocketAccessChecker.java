package cloud.bamsongi.albammate.room.contract;

/** WebSocket 주기 재검증이 ROOM 상태 보정과 현재 접근 확인을 분리해 호출하는 공개 계약이다. */
public interface ChatWebSocketAccessChecker {

	/** 해당 방의 현재 시각 기준 상태를 보정한다. */
	void correctRoomState(long roomId);

	/**
	 * 보정 뒤 현재 ROOM 상태와 주최자 또는 ACTIVE 참가 관계만 확인한다.
	 *
	 * <p>이 메서드는 상태 보정을 다시 호출하지 않으며, 메시지 전송·이력 조회·전달 직전의 강한 접근 검증을 대체하지 않는다.
	 */
	void verifyCurrentAccess(long currentUserId, long roomId);
}
