package cloud.bamsongi.albammate.auth.social;

import java.io.Serializable;
import java.util.Objects;

import cloud.bamsongi.albammate.user.contract.SocialProvider;

/**
 * 연결 시작이 서버 세션에 남기는 일회성 연결 의도다.
 *
 * <p>callback은 이 의도의 제공자·사용자가 모두 맞을 때만 연결하며, 한 번 쓰면 폐기한다.
 */
public record SocialLinkIntent(SocialProvider provider, Long userId) implements Serializable {

	public SocialLinkIntent {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(userId, "userId");
	}
}
