package cloud.bamsongi.albammate.chat.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 성공 커밋된 메시지 신호만 실시간 전달 포트로 넘긴다. */
@Component
@RequiredArgsConstructor
@Slf4j
class ChatMessageCommittedListener {

	private final ChatRealtimePublisher chatRealtimePublisher;

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
	public void publishAfterCommit(MessageCommitted event) {
		try {
			chatRealtimePublisher.publish(event);
		} catch (RuntimeException exception) {
			log.warn(
				"event=chat_realtime_publish_failed eventType={} roomId={} messageId={} exceptionType={}",
				event.eventType(),
				event.roomId(),
				event.messageId(),
				exception.getClass().getName());
		}
	}
}
