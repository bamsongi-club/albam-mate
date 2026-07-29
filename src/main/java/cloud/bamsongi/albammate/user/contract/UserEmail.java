package cloud.bamsongi.albammate.user.contract;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 사용자 모듈이 소유하는 이메일 정규화와 저장 불변식이다. */
public final class UserEmail {

	private final String value;

	private UserEmail(String value) {
		this.value = value;
	}

	public static Optional<UserEmail> from(String rawEmail) {
		if (rawEmail == null) {
			return Optional.empty();
		}

		String normalizedEmail = normalize(rawEmail);
		if (normalizedEmail.isEmpty()
			|| normalizedEmail.codePoints().anyMatch(Character::isISOControl)
			|| normalizedEmail.chars().anyMatch(Character::isWhitespace)) {
			return Optional.empty();
		}

		int atIndex = normalizedEmail.indexOf('@');
		boolean validStructure = atIndex > 0
			&& atIndex == normalizedEmail.lastIndexOf('@')
			&& atIndex < normalizedEmail.length() - 1
			&& normalizedEmail.charAt(atIndex - 1) != '.'
			&& normalizedEmail.charAt(atIndex + 1) != '.'
			&& !normalizedEmail.substring(atIndex + 1).contains("..");
		boolean validLength = normalizedEmail.codePointCount(0, normalizedEmail.length()) <= 255;
		if (validStructure && validLength) {
			return Optional.of(new UserEmail(normalizedEmail));
		}
		return Optional.empty();
	}

	public static String normalize(String rawEmail) {
		return Objects.requireNonNull(rawEmail, "rawEmail").strip().toLowerCase(Locale.ROOT);
	}

	public String value() {
		return value;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof UserEmail userEmail && value.equals(userEmail.value);
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}
}
