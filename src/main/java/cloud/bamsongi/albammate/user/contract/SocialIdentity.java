package cloud.bamsongi.albammate.user.contract;

import java.util.Objects;
import java.util.Optional;

/** auth가 신뢰·정규화를 마친 외부 신원 입력이다. */
public record SocialIdentity(
	SocialProvider provider,
	String providerSubject,
	Optional<UserEmail> email,
	Optional<UserNickname> nickname,
	Optional<String> profileImageUrl) {

	public SocialIdentity {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(providerSubject, "providerSubject");
		Objects.requireNonNull(email, "email");
		Objects.requireNonNull(nickname, "nickname");
		Objects.requireNonNull(profileImageUrl, "profileImageUrl");
		if (providerSubject.isBlank() || providerSubject.length() > 255) {
			throw new IllegalArgumentException("providerSubject must be 1 to 255 characters");
		}
		if (profileImageUrl.isPresent() && profileImageUrl.get().length() > 2048) {
			throw new IllegalArgumentException("profileImageUrl must be 2048 characters or less");
		}
	}
}
