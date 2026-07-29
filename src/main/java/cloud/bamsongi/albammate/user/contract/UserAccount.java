package cloud.bamsongi.albammate.user.contract;

/** 회원가입·프로필 기능이 사용자 모듈에서 받는 공개 계정 요약이다. */
public record UserAccount(Long id, String nickname) {

	public static UserAccount from(UserCredentials credentials) {
		return new UserAccount(credentials.id(), credentials.nickname());
	}
}
