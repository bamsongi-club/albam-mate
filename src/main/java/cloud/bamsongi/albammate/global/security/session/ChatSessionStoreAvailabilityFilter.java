package cloud.bamsongi.albammate.global.security.session;

import java.io.IOException;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * CHAT-03·CHAT-08 네 엔드포인트 전용 세션 저장소 가용성 gate다.
 *
 * <p>Spring Session의 {@code SessionRepositoryFilter}는 필터 경계에서 세션 저장소 조회 실패를 던지므로
 * {@code @RestControllerAdvice}가 처리하지 못한다. 이 필터는 Spring Security 인증이 세션을 실제로 조회하기 전에
 * 같은 저장소를 직접 확인하고, 확인할 수 없으면 인증·업그레이드 전에 {@code Retry-After} 없는 503으로 거절하며 인메모리
 * 저장소로 자동 전환하지 않는다. 적용 범위는 방별 채팅 POST·GET 메시지, 방별 GET WebSocket과 CHAT-08 채팅 목록 GET
 * WebSocket 네 경로로 한정하고 그 밖의 세션 사용 엔드포인트는 바꾸지 않는다.
 *
 * <p>{@code @Component}로 등록하지 않는 이유는 {@code Filter} 빈이 모든 {@code @WebMvcTest} slice에 자동
 * 포함되기 때문이다. {@link ChatSessionStoreAvailabilitySecurityConfiguration}이 이 인스턴스를 직접 만들어
 * {@code SecurityFilterChain}에만 추가한다.
 */
@RequiredArgsConstructor
final class ChatSessionStoreAvailabilityFilter extends OncePerRequestFilter {

	private static final String PROBE_SESSION_ID = "chat-session-store-availability-probe";

	private static final RequestMatcher CHAT_ENDPOINTS_MATCHER = new OrRequestMatcher(
		PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/rooms/{roomId}/chat/messages"),
		PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/rooms/{roomId}/chat/messages"),
		PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/rooms/{roomId}/chat/ws"),
		PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/users/me/chat/ws"));

	private final SessionRepository<? extends Session> sessionRepository;
	private final SecurityErrorResponseWriter responseWriter;

	@Override
	protected void doFilterInternal(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		if (!CHAT_ENDPOINTS_MATCHER.matches(request) || isSessionStoreAvailable()) {
			filterChain.doFilter(request, response);
			return;
		}
		responseWriter.write(response, ErrorCode.SERVICE_UNAVAILABLE);
	}

	private boolean isSessionStoreAvailable() {
		try {
			sessionRepository.findById(PROBE_SESSION_ID);
			return true;
		} catch (RuntimeException exception) {
			return false;
		}
	}
}
