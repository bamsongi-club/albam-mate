package cloud.bamsongi.albammate.chat.contract;

/**
 * CHAT-08이 정의한 사용자 단위 채팅 목록 팬아웃 진입점이다.
 *
 * <p>기존 방 단위 팬아웃({@link ChatRealtimeSignalGateway})과 같은 커밋 신호를 받아, 그 방의 현재 참가자 user id
 * 전체를 조회해 인스턴스 로컬 사용자 단위 WebSocket 연결에 최소 이벤트를 전달한다. 두 팬아웃은 같은 Redis 신호에서
 * 갈라지는 독립 경로이며, 이 경로의 실패는 방 단위 팬아웃이나 메시지 저장에 영향을 주지 않는다.
 */
public interface ChatRoomUpdatedSignalGateway {

	/**
	 * 참가자 조회·전달 실패를 이 메서드 밖으로 전파하지 않는 best-effort 팬아웃이다.
	 *
	 * @param event 신호가 담은 최소 사실({@code eventType}·{@code roomId}·{@code messageId})
	 */
	void onMessageCommitted(MessageCommitted event);
}
