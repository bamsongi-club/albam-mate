package cloud.bamsongi.albammate.chat.websocket;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.server.HandshakeHandler;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * ADR-0082가 정한 CHAT-08 사용자 단위 WebSocket handshake 인증 경계다.
 *
 * <p>기존 {@code JSESSIONID} 세션과 허용된 {@code Origin}만 검증하고, 방 단위 권한 검사는 하지 않는다. 어떤 방의
 * 이벤트를 받을지는 커밋 신호가 도착할 때마다 {@code room.contract}의 참가자 목록 조회로 그때그때 결정한다.
 */
@RestController
@RequiredArgsConstructor
public class ChatUserWebSocketHandshakeController {

	private final CurrentUserAccessor currentUserAccessor;
	private final ChatWebSocketProperties chatWebSocketProperties;
	private final HandshakeHandler chatHandshakeHandler;
	private final ChatUserWebSocketHandler chatUserWebSocketHandler;

	@GetMapping("/api/users/me/chat/ws")
	public void handshake(HttpServletRequest request, HttpServletResponse response) throws Exception {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		requireAllowedOrigin(request);
		HttpSession httpSession = request.getSession(false);
		if (httpSession == null) {
			throw new UnauthenticatedException();
		}
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(ChatUserWebSocketHandler.USER_ID_ATTRIBUTE, currentUserId);
		chatHandshakeHandler.doHandshake(
			new ServletServerHttpRequest(request),
			new ServletServerHttpResponse(response),
			chatUserWebSocketHandler,
			attributes);
	}

	private void requireAllowedOrigin(HttpServletRequest request) {
		String allowedOrigin = chatWebSocketProperties.getAllowedOrigin();
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (allowedOrigin.isBlank() || !allowedOrigin.equals(origin)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}
}
