package cloud.bamsongi.albammate.auth.social;

/**
 * callback이 same-site 프론트엔드로 돌려보내는 고정 결과다.
 *
 * <p>제공자가 보내는 값이나 사용자 속성을 리다이렉트 URL에 복사하지 않고, 여기 정의한 값만 사용한다.
 * {@code link-success}와 {@code link-conflict}는 로그인 사용자의 명시적 연결(AUTH-05c)이 도입할 결과이므로 아직 없다.
 */
enum SocialAuthResult {

	LOGIN_SUCCESS("login-success", "/home"),
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
