package cloud.bamsongi.albammate.chat.match.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import cloud.bamsongi.albammate.chat.match.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.MatchChatRealtimePublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

/**
 * 성공 커밋된 MATCH 채팅 메시지 신호만 실시간 전달 포트로 넘긴다.
 *
 * <p>전달 소요 시간과 실패 건수만 계측하며, partyId·messageId 이상의 어떤 식별자(사용자 ID 등)도 태그나 로그에 남기지 않는다.
 */
@Component
@Slf4j
class MatchChatMessageCommittedListener {

	private final MatchChatRealtimePublisher matchChatRealtimePublisher;
	private final Timer deliveryDuration;
	private final Counter deliveryFailures;

	MatchChatMessageCommittedListener(MatchChatRealtimePublisher matchChatRealtimePublisher,
		MeterRegistry meterRegistry) {
		this.matchChatRealtimePublisher = matchChatRealtimePublisher;
		this.deliveryDuration = Timer.builder("match.chat.message.delivery.duration").register(meterRegistry);
		this.deliveryFailures = Counter.builder("match.chat.message.delivery.failures").register(meterRegistry);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
	public void publishAfterCommit(MatchChatMessageCommitted event) {
		try {
			deliveryDuration.record(() -> matchChatRealtimePublisher.publish(event));
		} catch (RuntimeException exception) {
			deliveryFailures.increment();
			log.atWarn().addKeyValue("event", "match_chat_realtime_publish_failed")
				.addKeyValue("eventType", event.eventType()).addKeyValue("partyId", event.partyId())
				.addKeyValue("messageId", event.messageId())
				.addKeyValue("exceptionType", exception.getClass().getName())
				.log("match chat realtime publish failed");
		}
	}
}
