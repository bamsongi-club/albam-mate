package cloud.bamsongi.albammate.infra.redis;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Redis 세션 JSON이 현재 사용자 주체를 최소 필드만으로 복원하게 한다. */
abstract class CurrentUserPrincipalMixin {

	@JsonCreator
	CurrentUserPrincipalMixin(@JsonProperty("userId")
	long userId) {}
}
