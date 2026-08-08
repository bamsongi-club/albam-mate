package cloud.bamsongi.albammate.infra.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 채팅 전송 제한의 사용자 허용량·방 허용량·창 크기를 프로필별로 주입하는 속성이다.
 *
 * <p>기본값은 전송 제한 계약이 고정한 5건·30건·10초와 같다. */
@ConfigurationProperties("app.chat.rate-limit")
public record ChatMessageRateLimitProperties(
	@DefaultValue("5")
	int userLimit,
	@DefaultValue("30")
	int roomLimit,
	@DefaultValue("10s")
	Duration window) {
}
