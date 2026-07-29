package cloud.bamsongi.albammate.global.security.error;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 인증되지 않은 보호 API 요청을 공통 JSON 오류 봉투로 변환한다. */
@RequiredArgsConstructor
@Component
public final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

	@NonNull private final SecurityErrorResponseWriter responseWriter;

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException)
		throws IOException {
		responseWriter.write(response, ErrorCode.UNAUTHENTICATED);
	}
}
