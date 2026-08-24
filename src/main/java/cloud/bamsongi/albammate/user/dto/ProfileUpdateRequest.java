package cloud.bamsongi.albammate.user.dto;

import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.contract.ValidNickname;
import jakarta.validation.constraints.NotNull;

/** 프로필 수정 HTTP 요청 원문을 표현한다. */
public record ProfileUpdateRequest(@NotNull @ValidNickname
String nickname) {

	/**
	 * {@code @ValidNickname}이 {@link UserNickname#from(String)}과 같은 규칙을 쓰므로 검증 통과 뒤에는 항상 값이 있다.
	 */
	public UserNickname normalize() {
		return UserNickname.from(nickname).orElseThrow();
	}
}
