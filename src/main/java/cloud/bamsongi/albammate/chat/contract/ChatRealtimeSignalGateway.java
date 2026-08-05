package cloud.bamsongi.albammate.chat.contract;

/** Redis 등 전달 신호 채널이 수신한 커밋 신호를 방별 로컬 WebSocket 연결로 넘기는 진입점이다. */
public interface ChatRealtimeSignalGateway {

	/**
	 * 신호 payload를 그대로 전달하지 않고, 이 방의 로컬 연결이 PostgreSQL catch-up으로 누락분을 복구하도록 촉진한다.
	 *
	 * @param event 신호가 담은 최소 사실({@code eventType}·{@code roomId}·{@code messageId})
	 */
	void onMessageCommitted(MessageCommitted event);
}
