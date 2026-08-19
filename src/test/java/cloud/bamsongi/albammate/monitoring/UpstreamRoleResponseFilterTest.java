package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;

class UpstreamRoleResponseFilterTest {

	@Test
	void T3_5xx는_서버가_생성한_requestId와_유한_failureCode만_기록하고_4xx는_제외한다() throws Exception {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(UpstreamRoleResponseFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			MockHttpServletRequest serverFailureRequest = new MockHttpServletRequest("GET", "/api/games");
			serverFailureRequest.addHeader("X-Request-Id", "external-request-id");
			serverFailureRequest.setQueryString("secret=do-not-log");
			java.util.concurrent.atomic.AtomicReference<String> requestIdInChain = new java.util.concurrent.atomic.AtomicReference<>();
			new UpstreamRoleResponseFilter("app1").doFilter(serverFailureRequest, new MockHttpServletResponse(),
				(request, response) -> {
					requestIdInChain.set(org.slf4j.MDC.get("requestId"));
					((MockHttpServletResponse)response).setStatus(503);
				});
			assertTrue(requestIdInChain.get().matches("[0-9a-f-]{36}"));
			assertFalse("external-request-id".equals(requestIdInChain.get()));
			assertNull(org.slf4j.MDC.get("requestId"));
			MockHttpServletRequest clientFailureRequest = new MockHttpServletRequest("GET", "/api/games");
			new UpstreamRoleResponseFilter("app1").doFilter(clientFailureRequest, new MockHttpServletResponse(),
				(request, response) -> ((MockHttpServletResponse)response).setStatus(404));

			assertEquals(1, appender.list.size());
			assertEquals(Level.ERROR, appender.list.getFirst().getLevel());
			String fields = cloud.bamsongi.albammate.fixture.StructuredLogAssertions
				.fieldText(appender.list.getFirst());
			assertTrue(fields.contains("event=http_request_failed failureCode=HTTP_SERVER_ERROR"));
			assertEquals(requestIdInChain.get(), appender.list.getFirst().getMDCPropertyMap().get("requestId"));
			assertFalse(fields.contains("secret=do-not-log"));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void T3_일반_ERROR_dispatch는_최초_서버_requestId를_다시_바인딩하고_장애_로그를_중복하지_않는다()
		throws Exception {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(UpstreamRoleResponseFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			UpstreamRoleResponseFilter filter = new UpstreamRoleResponseFilter("app1");
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
			request.addHeader("X-Request-Id", "external-request-id");
			MockHttpServletResponse response = new MockHttpServletResponse();
			java.util.concurrent.atomic.AtomicReference<String> initialRequestId = new java.util.concurrent.atomic.AtomicReference<>();
			java.util.concurrent.atomic.AtomicReference<String> errorRequestId = new java.util.concurrent.atomic.AtomicReference<>();

			filter.doFilter(request, response, (servletRequest, servletResponse) -> {
				initialRequestId.set(org.slf4j.MDC.get("requestId"));
				((MockHttpServletResponse)servletResponse).setStatus(503);
			});
			assertNull(org.slf4j.MDC.get("requestId"));

			request.setDispatcherType(DispatcherType.ERROR);
			request.setAttribute("jakarta.servlet.error.request_uri", "/api/games");
			filter.doFilter(request, response,
				(servletRequest, servletResponse) -> errorRequestId.set(org.slf4j.MDC.get("requestId")));

			assertTrue(initialRequestId.get().matches("[0-9a-f-]{36}"));
			assertEquals(initialRequestId.get(), errorRequestId.get());
			assertNull(org.slf4j.MDC.get("requestId"));
			assertEquals(1, appender.list.size());
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void T3_filterChain_예외와_애플리케이션이_확정한_IOException_5xx만_서버오류를_기록하고_전달한다() throws Exception {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(UpstreamRoleResponseFilter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			ServletException servletException = new ServletException("filter chain failure");
			assertSame(servletException, assertThrows(ServletException.class,
				() -> new UpstreamRoleResponseFilter("app1").doFilter(sensitiveRequest(), new MockHttpServletResponse(),
					(servletRequest, response) -> {
						throw servletException;
					})));
			IllegalStateException runtimeException = new IllegalStateException("filter chain failure");
			assertSame(runtimeException, assertThrows(IllegalStateException.class,
				() -> new UpstreamRoleResponseFilter("app1").doFilter(sensitiveRequest(), new MockHttpServletResponse(),
					(servletRequest, response) -> {
						throw runtimeException;
					})));
			java.io.IOException clientAbort = new java.io.IOException("filter chain failure");
			assertSame(clientAbort, assertThrows(java.io.IOException.class,
				() -> throwingIOException(sensitiveRequest(), new MockHttpServletResponse(), clientAbort)));
			MockHttpServletResponse clientFailureResponse = new MockHttpServletResponse();
			clientFailureResponse.setStatus(404);
			assertThrows(java.io.IOException.class,
				() -> throwingIOException(sensitiveRequest(), clientFailureResponse,
					new java.io.IOException("filter chain failure")));
			assertEquals(2, appender.list.size());
			MockHttpServletResponse serverFailureResponse = new MockHttpServletResponse();
			serverFailureResponse.setStatus(503);
			assertThrows(java.io.IOException.class,
				() -> throwingIOException(sensitiveRequest(), serverFailureResponse,
					new java.io.IOException("filter chain failure")));
			assertEquals(3, appender.list.size());
			assertEquals(List.of(
				"event=http_request_failed failureCode=HTTP_SERVER_ERROR",
				"event=http_request_failed failureCode=HTTP_SERVER_ERROR",
				"event=http_request_failed failureCode=HTTP_SERVER_ERROR"),
				appender.list.stream().map(event -> {
					assertEquals(Level.ERROR, event.getLevel());
					String fields = cloud.bamsongi.albammate.fixture.StructuredLogAssertions.fieldText(event);
					assertFalse(fields.contains("secret=do-not-log"));
					return fields;
				}).toList());
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

	private MockHttpServletRequest sensitiveRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
		request.setQueryString("secret=do-not-log");
		return request;
	}

	private void throwingIOException(
		MockHttpServletRequest request, MockHttpServletResponse response, java.io.IOException exception)
		throws Exception {
		new UpstreamRoleResponseFilter("app1").doFilter(request, response,
			(servletRequest, servletResponse) -> {
				throw exception;
			});
	}
}
