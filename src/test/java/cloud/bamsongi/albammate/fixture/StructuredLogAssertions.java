package cloud.bamsongi.albammate.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.stream.Collectors;

import ch.qos.logback.classic.spi.ILoggingEvent;

/** SLF4J fluent structured logging의 업무 field와 사람이 읽는 message를 분리해 검증한다. */
public final class StructuredLogAssertions {

	private StructuredLogAssertions() {}

	public static void assertFields(ILoggingEvent event, Map<String, ?> expected) {
		Map<String, Object> actual = fields(event);
		assertEquals(expected, actual);
		assertFalse(event.getFormattedMessage().contains("="));
	}

	public static Map<String, Object> fields(ILoggingEvent event) {
		return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
	}

	public static String fieldText(ILoggingEvent event) {
		assertFalse(event.getFormattedMessage().contains("="));
		return event.getKeyValuePairs().stream()
			.map(pair -> pair.key + "=" + pair.value)
			.collect(Collectors.joining(" "));
	}
}
