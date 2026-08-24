package cloud.bamsongi.albammate.auth.social;

/**
 * callback이 same-site 프론트엔드로 돌려보내는 고정 결과다.
 *
 * <p>제공자가 보내는 값이나 사용자 속성을 리다이렉트 URL에 복사하지 않고, 여기 정의한 값만 사용한다. 결과 값은 시도 모드와 무관하게 같고, 돌아갈
 * 화면만 비로그인 로그인 시도와 로그인 사용자의 연결 시도가 서로 다르다.
 */
enum SocialAuthResult {

	LOGIN_SUCCESS("login-success", "/home", "/home"),
	LINK_SUCCESS("link-success", "/profile", "/profile"),
	LINK_CONFLICT("link-conflict", "/auth", "/profile"),
	LINK_REQUIRED("link-required", "/auth", "/auth"),
	CANCELED("canceled", "/auth", "/profile"),
	INVALID_STATE("invalid-state", "/auth", "/profile"),
	PROVIDER_UNAVAILABLE("provider-unavailable", "/auth", "/auth"),
	FAILED("failed", "/auth", "/profile");

	private final String value;
	private final String loginRoute;
	private final String linkRoute;

	SocialAuthResult(String value, String loginRoute, String linkRoute) {
		this.value = value;
		this.loginRoute = loginRoute;
		this.linkRoute = linkRoute;
	}

	String location(boolean linkAttempt) {
		return "/?socialAuth=" + value + "#" + (linkAttempt ? linkRoute : loginRoute);
	}
}
