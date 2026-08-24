package cloud.bamsongi.albammate.chat.websocket;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 사용자·방 식별자 없이 연결 수, 저장 후 전달 지연과 전달·복구 결과만 계측한다. */
@Component
class ChatWebSocketMetrics {

	private final AtomicInteger activeConnections = new AtomicInteger();
	private final Timer deliveryLatency;
	private final Counter deliveryFailures;
	private final Counter recoveredMessages;

	ChatWebSocketMetrics(MeterRegistry meterRegistry) {
		Gauge.builder("chat.websocket.connections.active", activeConnections, AtomicInteger::get)
			.register(meterRegistry);
		deliveryLatency = Timer.builder("chat.websocket.delivery.latency").register(meterRegistry);
		deliveryFailures = Counter.builder("chat.websocket.delivery.failures").register(meterRegistry);
		recoveredMessages = Counter.builder("chat.websocket.recovery.messages").register(meterRegistry);
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
