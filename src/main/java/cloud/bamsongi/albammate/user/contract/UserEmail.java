package cloud.bamsongi.albammate.user.contract;

import java.util.Locale;
import java.util.Optional;

/**
 * 사용자 모듈이 소유하는 이메일 정규화와 저장 불변식이다.
 *
 * <p>{@link #from(String)}이 유일한 입구다. 정규화만 하고 형식 검증을 건너뛰는 경로를 공개하지 않으므로, 이 타입의 인스턴스를 받은
 * 쪽은 값이 이미 정규화·검증됐다고 믿고 다시 검증하지 않는다.
 */
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

	private static String normalize(String rawEmail) {
		return rawEmail.strip().toLowerCase(Locale.ROOT);
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
