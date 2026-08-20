package cloud.bamsongi.albammate.global.security.error;

import java.io.IOException;

import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** StrictHttpFirewall이 DispatcherServlet 전에 거절한 요청을 공통 API 오류 봉투로 변환한다. */
@RequiredArgsConstructor
@Component
public final class ApiRequestRejectedHandler implements RequestRejectedHandler {

	@NonNull private final SecurityErrorResponseWriter responseWriter;

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		RequestRejectedException requestRejectedException)
		throws IOException {
		responseWriter.write(request, response, ErrorCode.VALIDATION_ERROR);
	}
}
