package cloud.bamsongi.albammate.global.security.currentuser;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;

/** 세션에 저장할 인증 주체의 최소 표현이다. 업무 정보나 인증정보를 담지 않는다. */
public record CurrentUserPrincipal(long userId) implements Principal, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	public CurrentUserPrincipal {
		if (userId <= 0) {
			throw new IllegalArgumentException("userId는 양수여야 합니다.");
		}
	}

	@Override
	public String getName() {
		return Long.toString(userId);
	}
}
