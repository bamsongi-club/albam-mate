package cloud.bamsongi.albammate.infra.redis;

import java.io.IOException;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.web.filter.OncePerRequestFilter;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Spring Session 필터 경계에서 Redis 연결·시스템 장애를 공통 503 응답으로 변환한다. */
@RequiredArgsConstructor
final class RedisSessionFailureFilter extends OncePerRequestFilter {

	private final SecurityErrorResponseWriter responseWriter;

	@Override
	protected void doFilterInternal(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		try {
			filterChain.doFilter(request, response);
		} catch (RuntimeException exception) {
			if (!isRedisFailure(exception)) {
				throw exception;
			}
			responseWriter.write(response, ErrorCode.SERVICE_UNAVAILABLE);
		}
	}

	private boolean isRedisFailure(Throwable exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof RedisConnectionFailureException || current instanceof RedisSystemException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
