package cloud.bamsongi.albammate.chat.match;

/** 커밋된 MATCH 채팅 메시지의 실시간 전달 신호를 외부 전달 계층으로 넘기는 포트다. */
public interface MatchChatRealtimePublisher {

	void publish(MatchChatMessageCommitted event);
}
