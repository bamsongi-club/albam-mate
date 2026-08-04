package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ChatMessageRetentionPropertiesTest {

	@Test
	void 기본_실행_상한은_잠금_임대보다_짧다() {
		assertTrue(new ChatMessageRetentionProperties().isRunDurationWithinLockLease());
	}

	@Test
	void 실행_상한이_잠금_임대와_같거나_길면_설정을_거절한다() {
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setLockAtMostFor(Duration.ofSeconds(5));
		properties.setMaxRunDuration(Duration.ofSeconds(5));

		assertFalse(properties.isRunDurationWithinLockLease());
	}

	@Test
	void 비어_있는_시간_설정은_NotNull_검증에_맡기고_비교하지_않는다() {
		ChatMessageRetentionProperties missingRunDuration = new ChatMessageRetentionProperties();
		missingRunDuration.setMaxRunDuration(null);
		ChatMessageRetentionProperties missingLease = new ChatMessageRetentionProperties();
		missingLease.setLockAtMostFor(null);

		assertTrue(missingRunDuration.isRunDurationWithinLockLease());
		assertTrue(missingLease.isRunDurationWithinLockLease());
	}
}
