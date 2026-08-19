package cloud.bamsongi.albammate.chat.match;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.server.HandshakeHandler;

import cloud.bamsongi.albammate.chat.websocket.ChatWebSocketProperties;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/**
 * ADR-0080이 정한 MATCH 채팅 Party별 WebSocket handshake 인증·인가 경계다.
 *
 * <p>기존 {@code JSESSIONID} 세션(전역 {@code AUTHENTICATED} 정책)과 허용된 {@code Origin}을 검증한 뒤,
 * {@link MatchPartyAccessQuery}로 현재 Party 관계·상태를 재확인하고 통과한 요청만 실제 WebSocket protocol upgrade를
 * 수행한다.
 */
@RestController
@RequiredArgsConstructor
public class MatchChatWebSocketHandshakeController {

	private final CurrentUserAccessor currentUserAccessor;
	private final MatchPartyAccessQuery matchPartyAccessQuery;
	private final ChatWebSocketProperties chatWebSocketProperties;
	private final HandshakeHandler chatHandshakeHandler;
	private final MatchChatWebSocketHandler matchChatWebSocketHandler;

	@GetMapping("/api/matches/parties/{partyId}/chat/ws")
	public void handshake(
		@PathVariable @Positive long partyId,
		@RequestParam(required = false) @Positive Long afterMessageId,
		HttpServletRequest request,
		HttpServletResponse response) throws Exception {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		requireAllowedOrigin(request);
		requireAllowed(matchPartyAccessQuery.evaluateChatAccess(currentUserId, partyId));
		HttpSession httpSession = request.getSession(false);
		if (httpSession == null) {
			throw new UnauthenticatedException();
		}
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(MatchChatWebSocketHandler.PARTY_ID_ATTRIBUTE, partyId);
		attributes.put(MatchChatWebSocketHandler.USER_ID_ATTRIBUTE, currentUserId);
		attributes.put(MatchChatWebSocketHandler.SESSION_ID_ATTRIBUTE, httpSession.getId());
		if (afterMessageId != null) {
			attributes.put(MatchChatWebSocketHandler.AFTER_MESSAGE_ID_ATTRIBUTE, afterMessageId);
		}
		chatHandshakeHandler.doHandshake(
			new ServletServerHttpRequest(request),
			new ServletServerHttpResponse(response),
			matchChatWebSocketHandler,
			attributes);
	}

	private void requireAllowedOrigin(HttpServletRequest request) {
		String allowedOrigin = chatWebSocketProperties.getAllowedOrigin();
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (allowedOrigin.isBlank() || !allowedOrigin.equals(origin)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}

	private void requireAllowed(MatchPartyChatAccess chatAccess) {
		if (chatAccess == MatchPartyChatAccess.NOT_ACTIVE) {
			throw new BusinessException(ErrorCode.MATCH_CHAT_NOT_ACTIVE);
		}
		if (chatAccess == MatchPartyChatAccess.FORBIDDEN) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
	}
}
