package cloud.bamsongi.albammate.chat.match.service;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.match.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.MatchChatRealtimePublisher;

/**
 * 아직 Redis 구현체(Stage B)가 없는 모든 프로필에서 커밋 후 포트를 안전하게 닫는 기본 구현이다.
 *
 * <p>Stage B가 RedisMatchChatRealtimePublisher를 추가할 때 이 구현을 {@code @Profile("!local & !production")}으로
 * 좁히고 Redis 구현체에 {@code @Profile({"local","production"})}을 붙인다.
 */
@Component
class NoOpMatchChatRealtimePublisher implements MatchChatRealtimePublisher {

	@Override
	public void publish(MatchChatMessageCommitted event) {
		// #744 Stage B Redis adapter가 이 포트를 대체한다.
	}
}
