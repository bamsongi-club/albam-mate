package cloud.bamsongi.albammate.chat.match;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** MATCH 채팅 WebSocket의 연결 수, 저장 후 전달 지연과 전달·복구 결과만 사용자·Party 식별자 없이 계측한다. */
@Component
class MatchChatWebSocketMetrics {

	private final AtomicInteger activeConnections = new AtomicInteger();
	private final Timer deliveryLatency;
	private final Counter deliveryFailures;
	private final Counter recoveredMessages;

	MatchChatWebSocketMetrics(MeterRegistry meterRegistry) {
		Gauge.builder("match.chat.websocket.connections.active", activeConnections, AtomicInteger::get)
			.register(meterRegistry);
		deliveryLatency = Timer.builder("match.chat.websocket.delivery.latency").register(meterRegistry);
		deliveryFailures = Counter.builder("match.chat.websocket.delivery.failures").register(meterRegistry);
		recoveredMessages = Counter.builder("match.chat.websocket.recovery.messages").register(meterRegistry);
	}

	void connectionOpened() {
		activeConnections.incrementAndGet();
	}

	void connectionClosed() {
		activeConnections.decrementAndGet();
	}

	int activeConnectionCount() {
		return activeConnections.get();
	}

	void recordDeliveryLatency(Duration latency) {
		deliveryLatency.record(latency);
	}

	void recordDeliveryFailure() {
		deliveryFailures.increment();
	}

	void recordRecoveredMessages(int count) {
		if (count > 0) {
			recoveredMessages.increment(count);
		}
	}
}
