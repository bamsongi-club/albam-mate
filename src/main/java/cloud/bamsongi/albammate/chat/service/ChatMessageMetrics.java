package cloud.bamsongi.albammate.chat.service;

import java.util.Objects;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 채팅 저장 요청의 최종 업무 결과를 전달·복구 계측과 분리한다. */
@Component
final class ChatMessageMetrics {

	private final Counter accepted;
	private final Counter rejected;
	private final Counter failed;

	ChatMessageMetrics(MeterRegistry meterRegistry) {
		Objects.requireNonNull(meterRegistry, "meterRegistry");
		accepted = counter(meterRegistry, "accepted");
		rejected = counter(meterRegistry, "rejected");
		failed = counter(meterRegistry, "failed");
	}

	void recordAccepted() {
		accepted.increment();
	}

	void recordRejected() {
		rejected.increment();
	}

	void recordFailed() {
		failed.increment();
	}

	private Counter counter(MeterRegistry meterRegistry, String outcome) {
		return Counter.builder("chat.message.operations").tag("outcome", outcome).register(meterRegistry);
	}
}
