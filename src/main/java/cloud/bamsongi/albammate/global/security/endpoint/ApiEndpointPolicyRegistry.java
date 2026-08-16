package cloud.bamsongi.albammate.global.security.endpoint;

import static cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointAuthenticationMode.AUTHENTICATED;
import static cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointAuthenticationMode.OPTIONAL_AUTHENTICATION;
import static cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointAuthenticationMode.PUBLIC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션이 제공하는 API의 인증·CSRF 정책 정본이다.
 *
 * <p>GET 정책은 Spring MVC가 제공하는 HEAD 동작에도 같은 인증 수준을 적용한다. HEAD는 별도 MVC 매핑이 아니므로 MVC-정책 대조에는 GET 정책
 * 하나만 사용한다.
 */
@Component
public final class ApiEndpointPolicyRegistry {

	/**
	 * 보호 리소스의 하위 경로 접두사다. 이 아래에서 정책을 등록하지 않은 경로는 공개가 아니라 인증 대상이 된다.
	 *
	 * <p>{@code SecurityConfig}의 마지막 규칙이 {@code anyRequest().permitAll()}이므로, 이 안전망이 없으면 새 하위 경로를
	 * 만들고 정책 등록을 빠뜨렸을 때 그 경로가 공개로 열린다.
	 */
	private static final List<String> PROTECTED_FUTURE_SUBPATH_PREFIXES = List.of("/api/auth/", "/api/games/",
		"/api/game-mechanisms/", "/api/game-categories/", "/api/game-themes/", "/api/game-rankings/", "/api/rooms/",
		"/api/users/me/", "/api/matches/");

	private final List<ApiEndpointPolicy> policies;

	public ApiEndpointPolicyRegistry() {
		this((Collection<ApiEndpointPolicy>) defaultPolicies());
	}

	private ApiEndpointPolicyRegistry(Collection<ApiEndpointPolicy> policies) {
		this.policies = List.copyOf(policies);
		validatePolicies(this.policies);
	}

	/** 등록된 정책 전체를 노출한다. 운영 경로는 쓰지 않으며 정책 대조 테스트만 호출한다. */
	public List<ApiEndpointPolicy> policies() {
		return policies;
	}

	public RequestMatcher publicRequestMatcher() {
		return requestMatcherFor(ApiEndpointAuthenticationMode.PUBLIC);
	}

	public RequestMatcher authenticatedRequestMatcher() {
		return requestMatcherFor(ApiEndpointAuthenticationMode.AUTHENTICATED);
	}

	public RequestMatcher knownEndpointPathMatcher() {
		return orMatcher(
			policies.stream()
				.map(ApiEndpointPolicy::pathPattern)
				.distinct()
				.<RequestMatcher>map(PathPatternRequestMatcher::pathPattern)
				.toList());
	}

	public RequestMatcher csrfProtectionRequestMatcher() {
		return orMatcher(
			policies.stream()
				.filter(ApiEndpointPolicy::csrfRequired)
				.flatMap(policy -> requestMatchersFor(policy).stream())
				.toList());
	}

	/** 정책에 없는 보호 하위 경로를 인증 대상으로 만드는 안전망이다. */
	public RequestMatcher protectedFutureSubpathMatcher() {
		return request -> {
			String requestPath = request.getRequestURI().substring(request.getContextPath().length());
			return PROTECTED_FUTURE_SUBPATH_PREFIXES.stream().anyMatch(requestPath::startsWith);
		};
	}

	/**
	 * MVC가 수집한 API 핸들러와 등록 정책이 정확히 일치하는지 검증하고, 어긋나면 예외를 던진다.
	 *
	 * <p>애플리케이션 부팅은 이 대조를 실행하지 않는다. {@code ApiEndpointPolicyRegistryTest}가 호출해 정책을 등록하지
	 * 않은 새 핸들러와 핸들러가 사라진 정책을 빌드 시점에 잡는다. 그래서 API를 추가·삭제하면
	 * {@code defaultPolicies()}도 같은 변경에서 갱신해야 테스트가 통과한다.
	 */
	public void assertMatchesMvcEndpoints(Collection<ApiEndpointMapping> mvcEndpoints) {
		Set<ApiEndpointMapping> actual = Set.copyOf(mvcEndpoints);
		Set<ApiEndpointMapping> registered = policies.stream()
			.map(
				policy -> new ApiEndpointMapping(
					policy.method(), policy.pathPattern()))
			.collect(Collectors.toUnmodifiableSet());

		Set<ApiEndpointMapping> unregistered = new LinkedHashSet<>(actual);
		unregistered.removeAll(registered);
		Set<ApiEndpointMapping> orphaned = new LinkedHashSet<>(registered);
		orphaned.removeAll(actual);
		if (unregistered.isEmpty() && orphaned.isEmpty()) {
			return;
		}
		throw new IllegalStateException(
			"API endpoint policy mismatch: unregistered="
				+ unregistered
				+ ", orphaned="
				+ orphaned);
	}

	static ApiEndpointPolicyRegistry forPolicies(List<ApiEndpointPolicy> policies) {
		return new ApiEndpointPolicyRegistry((Collection<ApiEndpointPolicy>) policies);
	}

	static ApiEndpointPolicyRegistry forContributors(List<ApiEndpointPolicyContributor> contributors) {
		return new ApiEndpointPolicyRegistry((Collection<ApiEndpointPolicy>) policiesFrom(contributors));
	}

	private RequestMatcher requestMatcherFor(ApiEndpointAuthenticationMode authenticationMode) {
		return orMatcher(
			policies.stream()
				.filter(policy -> policy.authenticationMode() == authenticationMode)
				.flatMap(policy -> requestMatchersFor(policy).stream())
				.toList());
	}

	private List<RequestMatcher> requestMatchersFor(ApiEndpointPolicy policy) {
		List<RequestMatcher> matchers = new ArrayList<>();
		matchers.add(PathPatternRequestMatcher.pathPattern(policy.method(), policy.pathPattern()));
		if (policy.method() == HttpMethod.GET) {
			matchers.add(
				PathPatternRequestMatcher.pathPattern(HttpMethod.HEAD, policy.pathPattern()));
		}
		return matchers;
	}

	private RequestMatcher orMatcher(List<RequestMatcher> matchers) {
		if (matchers.isEmpty()) {
			return request -> false;
		}
		return new OrRequestMatcher(matchers);
	}

	private void validatePolicies(List<ApiEndpointPolicy> policies) {
		Set<ApiEndpointMapping> seen = new LinkedHashSet<>();
		for (ApiEndpointPolicy policy : policies) {
			ApiEndpointMapping mapping = new ApiEndpointMapping(policy.method(), policy.pathPattern());
			if (!seen.add(mapping)) {
				throw new IllegalStateException("Duplicate API endpoint policy: " + mapping);
			}
			validateCsrfRequirement(policy, mapping);
		}
	}

	private void validateCsrfRequirement(ApiEndpointPolicy policy, ApiEndpointMapping mapping) {
		boolean csrfMandatory = isStateChanging(policy.method())
			&& policy.authenticationMode() == ApiEndpointAuthenticationMode.AUTHENTICATED;
		if (csrfMandatory && !policy.csrfRequired()) {
			throw new IllegalStateException(
				"Authenticated state-changing endpoint requires CSRF: " + mapping);
		}
	}

	private boolean isStateChanging(HttpMethod method) {
		return method == HttpMethod.POST
			|| method == HttpMethod.PUT
			|| method == HttpMethod.PATCH
			|| method == HttpMethod.DELETE;
	}

	private static List<ApiEndpointPolicy> defaultPolicies() {
		return List.of(
			policy(HttpMethod.GET, "/api/auth/csrf", PUBLIC, false),
			policy(HttpMethod.POST, "/api/auth/signup", PUBLIC, true),
			policy(HttpMethod.POST, "/api/auth/login", PUBLIC, true),
			policy(HttpMethod.POST, "/api/auth/logout", AUTHENTICATED, true),
			policy(HttpMethod.GET, "/api/auth/social/providers", OPTIONAL_AUTHENTICATION, false),
			policy(HttpMethod.GET, "/api/users/me", AUTHENTICATED, false),
			policy(HttpMethod.PATCH, "/api/users/me", AUTHENTICATED, true),
			policy(HttpMethod.POST, "/api/users/me/profile-image", AUTHENTICATED, true),
			policy(HttpMethod.DELETE, "/api/users/me/profile-image", AUTHENTICATED, true),
			policy(HttpMethod.POST, "/api/users/me/social-accounts/{provider}/link", AUTHENTICATED, true),
			policy(HttpMethod.GET, "/api/users/me/notifications", AUTHENTICATED, false),
			policy(HttpMethod.GET, "/api/users/me/notifications/unread-count", AUTHENTICATED, false),
			policy(HttpMethod.PATCH, "/api/users/me/notifications", AUTHENTICATED, true),
			policy(HttpMethod.PATCH, "/api/users/me/notifications/{notificationId}", AUTHENTICATED, true),
			policy(HttpMethod.PUT, "/api/users/me/played-games/{gameId}", AUTHENTICATED, true),
			policy(HttpMethod.DELETE, "/api/users/me/played-games/{gameId}", AUTHENTICATED, true),
			policy(HttpMethod.GET, "/api/games", OPTIONAL_AUTHENTICATION, false),
			policy(HttpMethod.GET, "/api/games/{gameId}", OPTIONAL_AUTHENTICATION, false),
			policy(HttpMethod.GET, "/api/game-mechanisms", OPTIONAL_AUTHENTICATION, false),
			policy(HttpMethod.GET, "/api/game-categories", OPTIONAL_AUTHENTICATION, false),
			policy(HttpMethod.GET, "/api/game-themes", OPTIONAL_AUTHENTICATION, false),
			// 랭킹은 요청자에 따라 결과가 달라지지 않아 세션을 읽지 않는다.
			policy(HttpMethod.GET, "/api/game-rankings", PUBLIC, false),
			policy(HttpMethod.GET, "/api/rooms", OPTIONAL_AUTHENTICATION, false),
			policy(HttpMethod.GET, "/api/rooms/{roomId}", OPTIONAL_AUTHENTICATION, false),
			policy(HttpMethod.POST, "/api/rooms", AUTHENTICATED, true),
			policy(HttpMethod.POST, "/api/rooms/{roomId}/participants", AUTHENTICATED, true),
			policy(HttpMethod.PATCH, "/api/rooms/{roomId}", AUTHENTICATED, true),
			policy(HttpMethod.DELETE, "/api/rooms/{roomId}", AUTHENTICATED, true),
			policy(HttpMethod.PATCH, "/api/rooms/{roomId}/status", AUTHENTICATED, true),
			policy(HttpMethod.DELETE, "/api/rooms/{roomId}/participants/me", AUTHENTICATED, true),
			policy(HttpMethod.POST, "/api/rooms/{roomId}/waitlist", AUTHENTICATED, true),
			policy(HttpMethod.GET, "/api/rooms/{roomId}/waitlist/me", AUTHENTICATED, false),
			policy(HttpMethod.DELETE, "/api/rooms/{roomId}/waitlist/me", AUTHENTICATED, true),
			policy(HttpMethod.POST, "/api/rooms/{roomId}/chat/messages", AUTHENTICATED, true),
			policy(HttpMethod.GET, "/api/rooms/{roomId}/chat/messages", AUTHENTICATED, false),
			policy(HttpMethod.GET, "/api/rooms/{roomId}/chat/ws", AUTHENTICATED, false),
			policy(HttpMethod.GET, "/api/users/me/rooms", AUTHENTICATED, false));
	}

	private static List<ApiEndpointPolicy> policiesFrom(List<ApiEndpointPolicyContributor> contributors) {
		List<ApiEndpointPolicy> policies = new ArrayList<>(defaultPolicies());
		contributors.forEach(contributor -> policies.addAll(contributor.policies()));
		return policies;
	}

	private static ApiEndpointPolicy policy(
		HttpMethod method,
		String pathPattern,
		ApiEndpointAuthenticationMode authenticationMode,
		boolean csrfRequired) {
		return new ApiEndpointPolicy(method, pathPattern, authenticationMode, csrfRequired);
	}
}
