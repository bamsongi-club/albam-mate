package cloud.bamsongi.albammate.chat.websocket;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.server.HandshakeHandler;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * ADR-0032가 정한 CHAT-03 방별 WebSocket handshake 인증·인가 경계다.
 *
 * <p>기존 {@code JSESSIONID} 세션(전역 {@code AUTHENTICATED} 정책)과 허용된 {@code Origin}을 검증한 뒤,
 * {@link ChatAccessGuard}로 현재 방 관계·상태를 재확인하고 통과한 요청만 실제 WebSocket protocol upgrade를 수행한다.
 */
@RestController
@RequiredArgsConstructor
public class ChatWebSocketHandshakeController {

	private final CurrentUserAccessor currentUserAccessor;
	private final ChatAccessGuard chatAccessGuard;
	private final ChatWebSocketProperties chatWebSocketProperties;
	private final HandshakeHandler chatHandshakeHandler;
	private final ChatWebSocketHandler chatWebSocketHandler;

	@GetMapping("/api/rooms/{roomId}/chat/ws")
	public void handshake(
		@PathVariable @Positive long roomId,
		HttpServletRequest request,
		HttpServletResponse response) throws Exception {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		requireAllowedOrigin(request);
		chatAccessGuard.executeWithAccess(currentUserId, roomId, () -> null);
		chatHandshakeHandler.doHandshake(
			new ServletServerHttpRequest(request),
			new ServletServerHttpResponse(response),
			chatWebSocketHandler,
			Map.of());
	}

	private void requireAllowedOrigin(HttpServletRequest request) {
		String allowedOrigin = chatWebSocketProperties.getAllowedOrigin();
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (allowedOrigin.isBlank() || !allowedOrigin.equals(origin)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}
}
