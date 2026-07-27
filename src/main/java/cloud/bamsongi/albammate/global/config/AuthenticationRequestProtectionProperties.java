package cloud.bamsongi.albammate.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 인증 요청 제한과 비밀번호 해시 동시 실행 한도의 설정이다. */
@ConfigurationProperties(prefix = "app.security.auth-request")
public class AuthenticationRequestProtectionProperties {

    private Duration window = Duration.ofMinutes(10);
    private int signupLimit = 5;
    private int loginLimit = 30;
    private int loginFailureLimit = 5;
    private int maxIpKeys = 10_000;
    private int maxFailureKeys = 10_000;
    private int hashSlots = 4;

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getSignupLimit() {
        return signupLimit;
    }

    public void setSignupLimit(int signupLimit) {
        this.signupLimit = signupLimit;
    }

    public int getLoginLimit() {
        return loginLimit;
    }

    public void setLoginLimit(int loginLimit) {
        this.loginLimit = loginLimit;
    }

    public int getLoginFailureLimit() {
        return loginFailureLimit;
    }

    public void setLoginFailureLimit(int loginFailureLimit) {
        this.loginFailureLimit = loginFailureLimit;
    }

    public int getMaxIpKeys() {
        return maxIpKeys;
    }

    public void setMaxIpKeys(int maxIpKeys) {
        this.maxIpKeys = maxIpKeys;
    }

    public int getMaxFailureKeys() {
        return maxFailureKeys;
    }

    public void setMaxFailureKeys(int maxFailureKeys) {
        this.maxFailureKeys = maxFailureKeys;
    }

    public int getHashSlots() {
        return hashSlots;
    }

    public void setHashSlots(int hashSlots) {
        this.hashSlots = hashSlots;
    }

    public void validate() {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("auth request window must be positive");
        }
        if (signupLimit < 1 || loginLimit < 1 || loginFailureLimit < 1) {
            throw new IllegalArgumentException("auth request limits must be positive");
        }
        if (maxIpKeys < 1 || maxFailureKeys < 1 || hashSlots < 1) {
            throw new IllegalArgumentException("auth request state limits must be positive");
        }
    }
}
