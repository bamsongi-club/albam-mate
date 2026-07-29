package cloud.bamsongi.albammate.user.contract;

/** 로그인 검증에 필요한 최소 자격증명 표현이다. 저장 해시는 HTTP 응답으로 변환하지 않는다. */
public record UserCredentials(Long id, String nickname, String passwordHash) {

	public UserCredentials {
		if (id == null || id <= 0) {
			throw new IllegalArgumentException("id must be positive");
		}
		if (nickname == null || nickname.isEmpty()) {
			throw new IllegalArgumentException("nickname must not be empty");
		}
		if (passwordHash == null || passwordHash.isEmpty()) {
			throw new IllegalArgumentException("passwordHash must not be empty");
		}
	}

	@Override
	public String toString() {
		return "UserCredentials[id=" + id + ", nickname=" + nickname + ", passwordHash=<redacted>]";
	}
}
