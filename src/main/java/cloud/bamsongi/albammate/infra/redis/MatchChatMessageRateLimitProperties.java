package cloud.bamsongi.albammate.infra.redis;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** MATCH 채팅 전송 제한의 사용자 허용량·Party 허용량·창 크기를 프로필별로 주입하는 속성이다.
 *
 * <p>기본값은 CHAT-T5가 고정한 사용자 5건·Party 30건·10초와 같다. */
@Validated
@ConfigurationProperties("app.match-chat.rate-limit")
public record MatchChatMessageRateLimitProperties(
	@Min(1) @DefaultValue("5")
	int userLimit,
	@Min(1) @DefaultValue("30")
	int partyLimit,
	@NotNull @DurationMin(millis = 1) @DefaultValue("10s")
	Duration window) {

	@AssertTrue(message = "must be convertible to milliseconds") public boolean isWindowConvertibleToMillis() {
		if (window == null) {
			return true;
		}
		try {
			window.toMillis();
			return true;
		} catch (ArithmeticException exception) {
			return false;
		}
	}
}
