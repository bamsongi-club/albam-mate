package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MonitoringStructuredLoggingCustomizerTest {

	@Test
	void T3_중앙_허용_목록은_gameId와_deterministicFailure만_추가하고_금지_필드는_닫아_둔다()
		throws ReflectiveOperationException {
		Field field = MonitoringStructuredLoggingCustomizer.class.getDeclaredField("ALLOWED_KEYS");
		field.setAccessible(true);
		Set<?> allowedKeys = (Set<?>)field.get(null);

		assertTrue(allowedKeys.contains("gameid"));
		assertTrue(allowedKeys.contains("deterministicfailure"));
		assertFalse(allowedKeys.contains("message"));
		assertFalse(allowedKeys.contains("stack_trace"));
		assertFalse(allowedKeys.contains("sourceeventids"));
		assertFalse(allowedKeys.contains("actoruserid"));
	}
}
