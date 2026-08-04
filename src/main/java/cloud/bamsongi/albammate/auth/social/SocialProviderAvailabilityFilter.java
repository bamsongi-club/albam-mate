package cloud.bamsongi.albammate.auth.social;

import java.io.IOException;

import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 지원하지 않거나 현재 설정되지 않은 제공자 요청을 외부로 보내지 않고 고정 결과로 돌려보낸다.
 *
 * <p>OAuth authorization·callback 경로는 Spring Security filter가 소유하므로 이 판정도 filter에서 한다. 등록되지 않은
 * 경로값을 그대로 흘리면 authorization 시작이 예외로 끝나고 오류 화면이 노출된다.
 */
@RequiredArgsConstructor
public final class SocialProviderAvailabilityFilter extends OncePerRequestFilter {

	@NonNull private final SocialClientRegistrationRepository clientRegistrationRepository;

	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	@Override
	protected void doFilterInternal(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		String registrationId = registrationId(request);
		if (registrationId != null
			&& clientRegistrationRepository.configuredProvider(registrationId).isEmpty()) {
			redirectStrategy.sendRedirect(
				request, response, SocialAuthResult.PROVIDER_UNAVAILABLE.location(false));
			return;
		}
		filterChain.doFilter(request, response);
	}

	/** authorization·callback 경로의 마지막 구간만 제공자 경로값으로 읽는다. */
	private String registrationId(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		String registrationId = suffixAfter(path, SocialClientRegistrationRepository.AUTHORIZATION_BASE_URI);
		if (registrationId == null) {
			registrationId = suffixAfter(path, SocialClientRegistrationRepository.CALLBACK_BASE_URI);
		}
		return registrationId;
	}

	private String suffixAfter(String path, String baseUri) {
		String prefix = baseUri + "/";
		if (!path.startsWith(prefix)) {
			return null;
		}
		String suffix = path.substring(prefix.length());
		return suffix.isEmpty() || suffix.contains("/") ? null : suffix;
	}
}
