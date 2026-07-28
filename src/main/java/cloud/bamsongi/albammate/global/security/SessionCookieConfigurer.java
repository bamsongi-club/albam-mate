package cloud.bamsongi.albammate.global.security;

import cloud.bamsongi.albammate.global.config.SecurityCookieProperties;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.http.Cookie;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** JSESSIONID의 생성·만료와 서블릿 세션 기본 속성을 같은 계약으로 적용한다. */
@Component
public final class SessionCookieConfigurer {

    private static final String SESSION_COOKIE_NAME = "JSESSIONID";
    private static final String COOKIE_PATH = "/";
    private static final String SAME_SITE = "Lax";

    private final SecurityCookieProperties properties;

    public SessionCookieConfigurer(SecurityCookieProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public void configureSessionCookie(ServletContext servletContext) {
        SessionCookieConfig cookieConfig = servletContext.getSessionCookieConfig();
        cookieConfig.setName(SESSION_COOKIE_NAME);
        cookieConfig.setPath(COOKIE_PATH);
        cookieConfig.setHttpOnly(true);
        cookieConfig.setSecure(properties.isSecure());
        cookieConfig.setAttribute("SameSite", SAME_SITE);
    }

    public Cookie sessionCookie(String value) {
        Cookie cookie = new Cookie(SESSION_COOKIE_NAME, value);
        applyAttributes(cookie);
        return cookie;
    }

    public Cookie expiredSessionCookie() {
        Cookie cookie = sessionCookie("");
        cookie.setMaxAge(0);
        return cookie;
    }

    private void applyAttributes(Cookie cookie) {
        cookie.setPath(COOKIE_PATH);
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.isSecure());
        cookie.setAttribute("SameSite", SAME_SITE);
    }
}
