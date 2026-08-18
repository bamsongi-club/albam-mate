package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;

class UpstreamRoleResponseFilterTest {

	@Test
	void T3_5xx는_requestId와_유한_failureCode만_기록하고_4xx는_제외한다() throws Exception {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(UpstreamRoleResponseFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			org.slf4j.MDC.put("requestId", "server-request-123");
			MockHttpServletRequest serverFailureRequest = new MockHttpServletRequest("GET", "/api/games");
			serverFailureRequest.setQueryString("secret=do-not-log");
			new UpstreamRoleResponseFilter("app1").doFilter(serverFailureRequest, new MockHttpServletResponse(),
				(request, response) -> ((MockHttpServletResponse)response).setStatus(503));
			MockHttpServletRequest clientFailureRequest = new MockHttpServletRequest("GET", "/api/games");
			new UpstreamRoleResponseFilter("app1").doFilter(clientFailureRequest, new MockHttpServletResponse(),
				(request, response) -> ((MockHttpServletResponse)response).setStatus(404));
			MockHttpServletRequest timeoutRequest = new MockHttpServletRequest("GET", "/api/games");
			new UpstreamRoleResponseFilter("app1").doFilter(timeoutRequest, new MockHttpServletResponse(),
				(request, response) -> ((MockHttpServletResponse)response).setStatus(504));

			assertEquals(2, appender.list.size());
			assertEquals(Level.ERROR, appender.list.getFirst().getLevel());
			String fields = cloud.bamsongi.albammate.fixture.StructuredLogAssertions
				.fieldText(appender.list.getFirst());
			assertTrue(fields.contains("event=http_request_failed failureCode=HTTP_SERVER_ERROR"));
			assertEquals("server-request-123", appender.list.getFirst().getMDCPropertyMap().get("requestId"));
			assertFalse(fields.contains("secret=do-not-log"));
			assertTrue(cloud.bamsongi.albammate.fixture.StructuredLogAssertions.fieldText(appender.list.get(1))
				.contains("event=http_request_failed failureCode=HTTP_TIMEOUT"));
		} finally {
			org.slf4j.MDC.clear();
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void T3_filterChain_예외는_서버오류_이벤트를_한번_기록하고_전달한다() throws Exception {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(UpstreamRoleResponseFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
			request.setQueryString("secret=do-not-log");
			assertThrows(ServletException.class,
				() -> new UpstreamRoleResponseFilter("app1").doFilter(request, new MockHttpServletResponse(),
					(servletRequest, response) -> {
						throw new ServletException("filter chain failure");
					}));
			assertThrows(java.io.IOException.class,
				() -> new UpstreamRoleResponseFilter("app1").doFilter(request, new MockHttpServletResponse(),
					(servletRequest, response) -> {
						throw new java.io.IOException("filter chain failure");
					}));
			assertThrows(IllegalStateException.class,
				() -> new UpstreamRoleResponseFilter("app1").doFilter(request, new MockHttpServletResponse(),
					(servletRequest, response) -> {
						throw new IllegalStateException("filter chain failure");
					}));

			assertEquals(3, appender.list.size());
			appender.list.forEach(event -> {
				assertEquals(Level.ERROR, event.getLevel());
				String fields = cloud.bamsongi.albammate.fixture.StructuredLogAssertions.fieldText(event);
				assertEquals("event=http_request_failed failureCode=HTTP_SERVER_ERROR", fields);
				assertFalse(fields.contains("secret=do-not-log"));
			});
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void T1_정확한_app_role만_수동_응답_헤더로_설정하고_나머지는_거부한다() throws Exception {
		for (String role : List.of("app1", "app2")) {
			MockHttpServletResponse response = responseFor(role);

			assertEquals(role, response.getHeader(UpstreamRoleResponseFilter.HEADER_NAME));
		}
		for (String invalidRole : List.of("", " app1", "app1 ", "APP1", "app1:8080", "127.0.0.1:8080")) {
			assertThrows(IllegalArgumentException.class, () -> new UpstreamRoleResponseFilter(invalidRole));
		}
	}

	@Test
	void T1_local과_production은_동일한_환경_role을_수동_응답_헤더_입력으로_연결한다() throws Exception {
		String local = Files.readString(Path.of("src/main/resources/application-local.yml"));
		String production = Files.readString(Path.of("src/main/resources/application-production.yml"));

		assertTrue(local.contains("upstream-role: ${ALBAM_MATE_ROLE:app1}"));
		assertTrue(production.contains("upstream-role: ${ALBAM_MATE_ROLE}"));
	}

	private MockHttpServletResponse responseFor(String role) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new UpstreamRoleResponseFilter(role).doFilter(new MockHttpServletRequest(), response,
			(request, servletResponse) -> {});
		return response;
	}
}
