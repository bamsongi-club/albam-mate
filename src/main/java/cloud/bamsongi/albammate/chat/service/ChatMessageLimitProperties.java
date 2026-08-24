package cloud.bamsongi.albammate.chat.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * CHAT-02 메시지 전송 요청 검증에 사용하는 {@code clientMessageId}·{@code content} 최대 길이를 프로필별로
 * 주입하는 속성이다.
 *
 * <p>기본값은 API 명세가 고정한 100자·500자와 같다.
 */
@Validated
@ConfigurationProperties(prefix = "app.chat.message")
public class ChatMessageLimitProperties {

	@Min(1) @Max(100) private int maxClientMessageIdLength = 100;
	@Min(1) @Max(500) private int maxContentLength = 500;

	public int getMaxClientMessageIdLength() {
		return maxClientMessageIdLength;
	}

	public void setMaxClientMessageIdLength(int maxClientMessageIdLength) {
		this.maxClientMessageIdLength = maxClientMessageIdLength;
	}

	public int getMaxContentLength() {
		return maxContentLength;
	}

	public void setMaxContentLength(int maxContentLength) {
		this.maxContentLength = maxContentLength;
	}
}
