package cloud.bamsongi.albammate.chat.contract;

/** 커밋된 채팅 메시지의 실시간 전달 신호를 외부 전달 계층으로 넘기는 포트다. */
public interface ChatRealtimePublisher {

	void publish(MessageCommitted event);
}
