package cloud.bamsongi.albammate.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 보안 쿠키에 적용할 운영 환경별 속성이다.
 *
 * <p>기본값은 운영 HTTPS를 위한 값이며, 로컬 HTTP 개발에서는 {@code app.security.cookie.secure=false}를 명시해 두 쿠키의
 * Secure를 비활성화한다.
 */
@ConfigurationProperties(prefix = "app.security.cookie")
public class SecurityCookieProperties {

	private boolean secure = true;

	public boolean isSecure() {
		return secure;
	}

	public void setSecure(boolean secure) {
		this.secure = secure;
	}
}
