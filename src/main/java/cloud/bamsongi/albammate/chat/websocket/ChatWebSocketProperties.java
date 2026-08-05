package cloud.bamsongi.albammate.chat.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CHAT-03 WebSocket handshake의 허용 {@code Origin}을 프로필별로 주입하는 속성이다.
 *
 * <p>기본값은 {@code local}·{@code local-multi}가 고정한 프런트엔드 개발 서버 Origin이다. {@code production}은
 * 운영 도메인을 하드코딩하지 않고 환경변수로 주입하며, 값이 비어 있으면 모든 handshake를 거절한다.
 */
@ConfigurationProperties(prefix = "app.chat.websocket")
public class ChatWebSocketProperties {

	private String allowedOrigin = "http://localhost:5173";

	public String getAllowedOrigin() {
		return allowedOrigin;
	}

	public void setAllowedOrigin(String allowedOrigin) {
		this.allowedOrigin = allowedOrigin;
	}
}
