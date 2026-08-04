package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ChatMessageRetentionPropertiesTest {

	@Test
	void 기본_실행_상한은_진행_중인_chunk_질의를_더해도_잠금_임대보다_짧다() {
		assertTrue(new ChatMessageRetentionProperties().isRunDurationWithinLockLease());
	}

	@Test
	void 실행_상한과_질의_상한_합이_잠금_임대_이상이면_설정을_거절한다() {
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setLockAtMostFor(Duration.ofSeconds(60));
		properties.setMaxRunDuration(Duration.ofSeconds(31));
		properties.setQueryTimeout(Duration.ofSeconds(10));

		assertFalse(properties.isRunDurationWithinLockLease());
	}

	@Test
	void 실행_상한만_임대보다_짧아도_질의_상한을_더해_넘치면_거절한다() {
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setLockAtMostFor(Duration.ofSeconds(30));
		properties.setMaxRunDuration(Duration.ofSeconds(25));
		properties.setQueryTimeout(Duration.ofSeconds(10));

		assertFalse(properties.isRunDurationWithinLockLease());
	}

	@Test
	void 비어_있는_시간_설정은_NotNull_검증에_맡기고_비교하지_않는다() {
		ChatMessageRetentionProperties missingRunDuration = new ChatMessageRetentionProperties();
		missingRunDuration.setMaxRunDuration(null);
		ChatMessageRetentionProperties missingQueryTimeout = new ChatMessageRetentionProperties();
		missingQueryTimeout.setQueryTimeout(null);
		ChatMessageRetentionProperties missingLease = new ChatMessageRetentionProperties();
		missingLease.setLockAtMostFor(null);

		assertTrue(missingRunDuration.isRunDurationWithinLockLease());
		assertTrue(missingQueryTimeout.isRunDurationWithinLockLease());
		assertTrue(missingLease.isRunDurationWithinLockLease());
	}
}
