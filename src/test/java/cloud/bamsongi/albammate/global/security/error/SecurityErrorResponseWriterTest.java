package cloud.bamsongi.albammate.global.security.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import tools.jackson.databind.ObjectMapper;

class SecurityErrorResponseWriterTest {

	@Test
	void T3_보안_거절_게임_상태_변경은_허용된_구조화_key_value로_기록한다() throws Exception {
		SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(new ObjectMapper());
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(SecurityErrorResponseWriter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			writer.write(request("PUT", "/api/users/me/played-games/7"), new MockHttpServletResponse(),
				ErrorCode.UNAUTHENTICATED);
			writer.write(request("DELETE", "/api/users/me/played-games/0"), new MockHttpServletResponse(),
				ErrorCode.FORBIDDEN);

			assertFields(appender.list.get(0), "mark", ErrorCode.UNAUTHENTICATED, 7L);
			assertFields(appender.list.get(1), "unmark", ErrorCode.FORBIDDEN, null);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private MockHttpServletRequest request(String method, String requestUri) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, requestUri);
		request.setRequestURI(requestUri);
		return request;
	}

	private void assertFields(ILoggingEvent event, String action, ErrorCode errorCode, Long gameId) {
		Map<String, Object> fields = event.getKeyValuePairs().stream()
			.collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));

		assertEquals("game_played_state_change_failed", fields.get("event"));
		assertEquals(action, fields.get("action"));
		assertEquals("rejected", fields.get("outcome"));
		assertEquals(errorCode.getCode(), fields.get("failureCode"));
		if (gameId == null) {
			assertFalse(fields.containsKey("gameId"));
		} else {
			assertEquals(gameId, fields.get("gameId"));
		}
	}
}
