package cloud.bamsongi.albammate.chat.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * 성공 커밋된 메시지 신호만 실시간 전달 포트로 넘긴다.
 *
 * <p>전달 소요 시간과 실패 건수만 계측하며, roomId·messageId 이상의 어떤 식별자(사용자 ID 등)도 태그나 로그에 남기지 않는다.
 */
@Component
@Slf4j
class ChatMessageCommittedListener {

	private final ChatRealtimePublisher chatRealtimePublisher;
	private final Timer deliveryDuration;
	private final Counter deliveryFailures;

	ChatMessageCommittedListener(ChatRealtimePublisher chatRealtimePublisher, MeterRegistry meterRegistry) {
		this.chatRealtimePublisher = chatRealtimePublisher;
		this.deliveryDuration = Timer.builder("chat.message.delivery.duration").register(meterRegistry);
		this.deliveryFailures = Counter.builder("chat.message.delivery.failures").register(meterRegistry);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
	public void publishAfterCommit(MessageCommitted event) {
		try {
			deliveryDuration.record(() -> chatRealtimePublisher.publish(event));
		} catch (RuntimeException exception) {
			deliveryFailures.increment();
			log.atWarn().addKeyValue("event", "chat_realtime_publish_failed")
				.addKeyValue("eventType", event.eventType()).addKeyValue("roomId", event.roomId())
				.addKeyValue("messageId", event.messageId())
				.addKeyValue("exceptionType", exception.getClass().getName())
				.log("chat realtime publish failed");
		}
	}
}
