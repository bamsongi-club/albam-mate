package cloud.bamsongi.albammate.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 비밀번호 저장 알고리즘의 운영 설정이다.
 *
 * <p>제약은 Spring이 설정을 바인딩할 때 한 번 검사한다. 값이 범위를 벗어나면 애플리케이션이 뜨지 않으므로, 이 값을 쓰는 쪽은
 * 다시 검증하지 않는다.
 */
@Validated
@ConfigurationProperties(prefix = "app.security.password")
public class PasswordSecurityProperties {

	@Min(10) @Max(31) private int bcryptCost = 10;

	public int getBcryptCost() {
		return bcryptCost;
	}

	public void setBcryptCost(int bcryptCost) {
		this.bcryptCost = bcryptCost;
	}
}
