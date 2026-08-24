package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;

class RedisSessionFailureFilterTest {

	private final SecurityErrorResponseWriter responseWriter = mock(SecurityErrorResponseWriter.class);
	private final RedisSessionFailureFilter filter = new RedisSessionFailureFilter(responseWriter);

	@Test
	void Redis가_아닌_예외는_기존_500_예외_경계로_그대로_전파한다() throws Exception {
		FilterChain chain = mock(FilterChain.class);
		IllegalStateException exception = new IllegalStateException("unexpected session failure");
		doThrow(exception).when(chain).doFilter(any(), any());

		IllegalStateException thrown = assertThrows(
			IllegalStateException.class,
			() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain));

		assertSame(exception, thrown);
		verify(responseWriter, never()).write(any(), any());
	}

	@Test
	void Redis_연결_실패와_시스템_실패만_503_응답_작성기로_변환한다() throws Exception {
		assertRedisFailureIsConverted(new RedisConnectionFailureException("redis unavailable"));
		assertRedisFailureIsConverted(
			new RedisSystemException("redis unavailable", new RuntimeException("connection refused")));
	}

	private void assertRedisFailureIsConverted(RuntimeException exception) throws Exception {
		FilterChain chain = mock(FilterChain.class);
		doThrow(exception).when(chain).doFilter(any(), any());
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(new MockHttpServletRequest(), response, chain);

		verify(responseWriter).write(response, ErrorCode.SERVICE_UNAVAILABLE);
	}
}
