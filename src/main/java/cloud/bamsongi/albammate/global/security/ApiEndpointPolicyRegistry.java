package cloud.bamsongi.albammate.global.security;

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

    private static final List<String> PROTECTED_FUTURE_SUBPATH_PREFIXES =
            List.of("/api/auth/", "/api/games/", "/api/rooms/", "/api/users/me/");

    private final List<ApiEndpointPolicy> policies;

    public ApiEndpointPolicyRegistry() {
        this(defaultPolicies());
    }

    private ApiEndpointPolicyRegistry(List<ApiEndpointPolicy> policies) {
        this.policies = List.copyOf(policies);
        validatePolicies(this.policies);
    }

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

    public RequestMatcher protectedFutureSubpathMatcher() {
        return request -> {
            String requestPath =
                    request.getRequestURI().substring(request.getContextPath().length());
            return PROTECTED_FUTURE_SUBPATH_PREFIXES.stream().anyMatch(requestPath::startsWith);
        };
    }

    /** MVC가 수집한 API 핸들러와 등록 정책이 일치하는지 검증한다. */
    public void assertMatchesMvcEndpoints(Collection<ApiEndpointMapping> mvcEndpoints) {
        Set<ApiEndpointMapping> actual = Set.copyOf(mvcEndpoints);
        Set<ApiEndpointMapping> registered =
                policies.stream()
                        .map(
                                policy ->
                                        new ApiEndpointMapping(
                                                policy.method(), policy.pathPattern()))
                        .collect(Collectors.toUnmodifiableSet());

        Set<ApiEndpointMapping> unregistered = new LinkedHashSet<>(actual);
        unregistered.removeAll(registered);
        Set<ApiEndpointMapping> orphaned = new LinkedHashSet<>(registered);
        orphaned.removeAll(actual);
        if (!unregistered.isEmpty() || !orphaned.isEmpty()) {
            throw new IllegalStateException(
                    "API endpoint policy mismatch: unregistered="
                            + unregistered
                            + ", orphaned="
                            + orphaned);
        }
    }

    static ApiEndpointPolicyRegistry forPolicies(List<ApiEndpointPolicy> policies) {
        return new ApiEndpointPolicyRegistry(policies);
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
            ApiEndpointMapping mapping =
                    new ApiEndpointMapping(policy.method(), policy.pathPattern());
            if (!seen.add(mapping)) {
                throw new IllegalStateException("Duplicate API endpoint policy: " + mapping);
            }
            if (isStateChanging(policy.method())
                    && policy.authenticationMode() == ApiEndpointAuthenticationMode.AUTHENTICATED
                    && !policy.csrfRequired()) {
                throw new IllegalStateException(
                        "Authenticated state-changing endpoint requires CSRF: " + mapping);
            }
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
                policy(
                        HttpMethod.GET,
                        "/api/auth/csrf",
                        ApiEndpointAuthenticationMode.PUBLIC,
                        false),
                policy(
                        HttpMethod.POST,
                        "/api/auth/signup",
                        ApiEndpointAuthenticationMode.PUBLIC,
                        true),
                policy(
                        HttpMethod.POST,
                        "/api/auth/login",
                        ApiEndpointAuthenticationMode.PUBLIC,
                        true),
                policy(
                        HttpMethod.POST,
                        "/api/auth/logout",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        true),
                policy(
                        HttpMethod.GET,
                        "/api/users/me",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        false),
                policy(
                        HttpMethod.PATCH,
                        "/api/users/me",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        true),
                policy(
                        HttpMethod.GET,
                        "/api/games",
                        ApiEndpointAuthenticationMode.OPTIONAL_AUTHENTICATION,
                        false),
                policy(
                        HttpMethod.GET,
                        "/api/games/{gameId}",
                        ApiEndpointAuthenticationMode.OPTIONAL_AUTHENTICATION,
                        false),
                policy(
                        HttpMethod.GET,
                        "/api/rooms",
                        ApiEndpointAuthenticationMode.OPTIONAL_AUTHENTICATION,
                        false),
                policy(
                        HttpMethod.GET,
                        "/api/rooms/{roomId}",
                        ApiEndpointAuthenticationMode.OPTIONAL_AUTHENTICATION,
                        false),
                policy(
                        HttpMethod.POST,
                        "/api/rooms",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        true),
                policy(
                        HttpMethod.POST,
                        "/api/rooms/{roomId}/participants",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        true),
                policy(
                        HttpMethod.PATCH,
                        "/api/rooms/{roomId}",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        true),
                policy(
                        HttpMethod.DELETE,
                        "/api/rooms/{roomId}",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        true),
                policy(
                        HttpMethod.PATCH,
                        "/api/rooms/{roomId}/status",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        true),
                policy(
                        HttpMethod.DELETE,
                        "/api/rooms/{roomId}/participants/me",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        true),
                policy(
                        HttpMethod.GET,
                        "/api/users/me/rooms",
                        ApiEndpointAuthenticationMode.AUTHENTICATED,
                        false));
    }

    private static ApiEndpointPolicy policy(
            HttpMethod method,
            String pathPattern,
            ApiEndpointAuthenticationMode authenticationMode,
            boolean csrfRequired) {
        return new ApiEndpointPolicy(method, pathPattern, authenticationMode, csrfRequired);
    }
}
