package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.user.contract.UserAccount;

/** API가 공개하는 최소 사용자 요약이다. 이메일·비밀번호와 인증 정보는 포함하지 않는다. */
public record UserSummary(Long id, String nickname) {

	public static UserSummary from(UserAccount account) {
		return new UserSummary(account.id(), account.nickname());
	}
}
