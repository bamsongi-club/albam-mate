package cloud.bamsongi.albammate.user.contract;

import java.util.Optional;

/**
 * 사용자 모듈이 소유하는 닉네임 정규화와 불변식이다.
 *
 * <p>{@link #from(String)}이 유일한 입구다. 정규화만 하고 길이·제어문자 검증을 건너뛰는 경로를 공개하지 않으므로, 이 타입의
 * 인스턴스를 받은 쪽은 값이 이미 정규화·검증됐다고 믿고 다시 검증하지 않는다.
 */
public final class UserNickname {

	private final String value;

	private UserNickname(String value) {
		this.value = value;
	}

	public static Optional<UserNickname> from(String rawNickname) {
		if (rawNickname == null) {
			return Optional.empty();
		}

		String normalizedNickname = normalize(rawNickname);
		int codePointCount = normalizedNickname.codePointCount(0, normalizedNickname.length());
		if (codePointCount < 1
			|| codePointCount > 50
			|| normalizedNickname.codePoints().anyMatch(Character::isISOControl)) {
			return Optional.empty();
		}
		return Optional.of(new UserNickname(normalizedNickname));
	}

	private static String normalize(String rawNickname) {
		return rawNickname.strip();
	}

	public String value() {
		return value;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof UserNickname userNickname && value.equals(userNickname.value);
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}
}
