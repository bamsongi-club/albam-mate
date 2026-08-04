package cloud.bamsongi.albammate.auth.social;

/**
 * callback이 same-site 프론트엔드로 돌려보내는 고정 결과다.
 *
 * <p>제공자가 보내는 값이나 사용자 속성을 리다이렉트 URL에 복사하지 않고, 여기 정의한 값만 사용한다.
 */
enum SocialAuthResult {

	LOGIN_SUCCESS("login-success", "/home"),
	LINK_SUCCESS("link-success", "/profile"),
	LINK_CONFLICT("link-conflict", "/profile"),
	LINK_REQUIRED("link-required", "/auth"),
	CANCELED("canceled", "/auth"),
	INVALID_STATE("invalid-state", "/auth"),
	PROVIDER_UNAVAILABLE("provider-unavailable", "/auth"),
	FAILED("failed", "/auth");

	private final String value;
	private final String route;

	SocialAuthResult(String value, String route) {
		this.value = value;
		this.route = route;
	}

	String location() {
		return "/?socialAuth=" + value + "#" + route;
	}
}
