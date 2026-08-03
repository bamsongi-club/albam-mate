package cloud.bamsongi.albammate.user.contract;

import java.util.Objects;

/** 비로그인 소셜 첫 처리의 사용자 공개 결과다. */
public sealed interface SocialLoginResult permits SocialLoginResult.LoggedIn, SocialLoginResult.LinkRequired {

	static LoggedIn loggedIn(UserAccount account) {
		return new LoggedIn(account);
	}

	static LinkRequired linkRequired() {
		return new LinkRequired();
	}

	record LoggedIn(UserAccount account) implements SocialLoginResult {

		public LoggedIn {
			Objects.requireNonNull(account, "account");
		}
	}

	record LinkRequired() implements SocialLoginResult {}
}
