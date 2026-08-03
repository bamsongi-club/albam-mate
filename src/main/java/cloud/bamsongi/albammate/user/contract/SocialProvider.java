package cloud.bamsongi.albammate.user.contract;

/** AUTH-05에서 지원하는 외부 신원 제공자와 첫 로그인 기본 표시명이다. */
public enum SocialProvider {
	GOOGLE("Google 사용자"),
	NAVER("Naver 사용자"),
	KAKAO("Kakao 사용자");

	private final String fallbackNickname;

	SocialProvider(String fallbackNickname) {
		this.fallbackNickname = fallbackNickname;
	}

	public String fallbackNickname() {
		return fallbackNickname;
	}
}
