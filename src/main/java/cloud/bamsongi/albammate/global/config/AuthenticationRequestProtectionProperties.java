package cloud.bamsongi.albammate.global.config;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 인증 요청 제한과 비밀번호 해시 동시 실행 한도의 설정이다.
 *
 * <p>제약은 Spring이 설정을 바인딩할 때 한 번 검사한다. 값이 범위를 벗어나면 애플리케이션이 뜨지 않으므로, 이 값을 쓰는 쪽은
 * 다시 검증하지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "app.security.auth-request")
public class AuthenticationRequestProtectionProperties {

	@NotNull @DurationMin(nanos = 1)
	private Duration window = Duration.ofMinutes(10);

	@Min(1) private int signupLimit = 5;

	@Min(1) private int loginLimit = 30;

	@Min(1) private int loginFailureLimit = 5;

	@Min(1) private int maxIpKeys = 10_000;

	@Min(1) private int maxFailureKeys = 10_000;

	@Min(1) private int hashSlots = 4;

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
}
