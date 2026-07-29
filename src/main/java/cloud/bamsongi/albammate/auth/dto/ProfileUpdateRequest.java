package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.validation.ValidNickname;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import jakarta.validation.constraints.NotNull;

/** 프로필 수정 HTTP 요청 원문을 표현한다. */
public record ProfileUpdateRequest(@NotNull @ValidNickname
String nickname) {

	public String normalize() {
		return UserNickname.normalize(nickname);
	}
}
