package cloud.bamsongi.albammate.global.security.currentuser;

import java.util.Optional;

import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;

/** 여러 업무 모듈이 현재 인증 사용자의 내부 ID를 조회하기 위한 최소 기술 계약이다. */
public interface CurrentUserAccessor {

	/** 현재 인증된 사용자의 ID를 반환하며, 비로그인 요청이면 빈 값을 반환한다. */
	Optional<Long> currentUserId();

	/** 인증이 필수인 업무 흐름에서 현재 사용자의 ID를 반환한다. */
	default long requireCurrentUserId() {
		return currentUserId().orElseThrow(UnauthenticatedException::new);
	}
}
