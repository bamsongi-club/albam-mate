package cloud.bamsongi.albammate.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 보안 쿠키에 적용할 운영 환경별 속성이다.
 *
 * <p>기본값은 로컬 HTTP 개발을 위한 값이며, 운영 HTTPS에서는 {@code app.security.cookie.secure=true}로 두 쿠키에 Secure를
 * 적용한다.
 */
@ConfigurationProperties(prefix = "app.security.cookie")
public class SecurityCookieProperties {

    private boolean secure;

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }
}
