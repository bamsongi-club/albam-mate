package cloud.bamsongi.albammate.global.security.endpoint;

import java.util.Objects;

import org.springframework.http.HttpMethod;

/** HTTP 메서드·경로별 인증과 CSRF 정책을 표현한다. */
public record ApiEndpointPolicy(
	HttpMethod method,
	String pathPattern,
	ApiEndpointAuthenticationMode authenticationMode,
	boolean csrfRequired) {

	public ApiEndpointPolicy {
		Objects.requireNonNull(method, "method");
		Objects.requireNonNull(pathPattern, "pathPattern");
		Objects.requireNonNull(authenticationMode, "authenticationMode");
		if (!pathPattern.startsWith("/api/")) {
			throw new IllegalArgumentException("API policy path must start with /api/");
		}
	}
}
