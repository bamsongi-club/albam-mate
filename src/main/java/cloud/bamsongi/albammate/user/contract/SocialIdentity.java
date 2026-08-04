package cloud.bamsongi.albammate.user.contract;

import java.util.Objects;
import java.util.Optional;

/** auth가 신뢰·정규화를 마친 외부 신원 입력이다. */
public record SocialIdentity(
	SocialProvider provider,
	String providerSubject,
	Optional<UserEmail> email,
	Optional<UserNickname> nickname) {

	public SocialIdentity {
		Objects.requireNonNull(provider, "provider");
		Objects.requireNonNull(providerSubject, "providerSubject");
		Objects.requireNonNull(email, "email");
		Objects.requireNonNull(nickname, "nickname");
		if (providerSubject.isBlank() || providerSubject.length() > 255) {
			throw new IllegalArgumentException("providerSubject must be 1 to 255 characters");
		}
	}
}
