package cloud.bamsongi.albammate.auth.social;

import java.io.IOException;
import java.util.Optional;

import org.springframework.web.filter.OncePerRequestFilter;

import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * callback을 처리하기 전의 앱 로그인 사용자를 요청에 남긴다.
 *
 * <p>OAuth filter는 인증에 성공하면 성공 처리 직전에 세션 인증을 외부 principal로 덮는다. 그래서 성공 처리 시점에는 연결 의도를 만든
 * 사용자가 지금도 이 세션의 사용자인지 확인할 수 없다. 이 filter가 덮이기 전 값을 읽어 두어야 연결 대상 검증이 가능하다.
 */
@RequiredArgsConstructor
public final class SocialLinkCurrentUserFilter extends OncePerRequestFilter {

	static final String CURRENT_USER_ID = SocialLinkCurrentUserFilter.class.getName() + ".CURRENT_USER_ID";

	@NonNull private final CurrentUserAccessor currentUserAccessor;

	static Optional<Long> currentUserId(HttpServletRequest request) {
		return Optional.ofNullable(request.getAttribute(CURRENT_USER_ID)).map(Long.class::cast);
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		currentUserAccessor.currentUserId()
			.ifPresent(userId -> request.setAttribute(CURRENT_USER_ID, userId));
		filterChain.doFilter(request, response);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !path.startsWith(SocialClientRegistrationRepository.CALLBACK_BASE_URI + "/");
	}
}
