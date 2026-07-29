package cloud.bamsongi.albammate.user.contract;

import java.util.Optional;

/** 회원가입 비밀번호 정책을 만족하는 원문 비밀번호다. */
public final class RawPassword {

	private final String value;

	private RawPassword(String value) {
		this.value = value;
	}

	public static Optional<RawPassword> from(String rawPassword) {
		if (UserPasswordPolicy.isValidSignupPassword(rawPassword)) {
			return Optional.of(new RawPassword(rawPassword));
		}
		return Optional.empty();
	}

	public String value() {
		return value;
	}

	@Override
	public String toString() {
		return "RawPassword[REDACTED]";
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof RawPassword rawPassword && value.equals(rawPassword.value);
	}

	@Override
	public int hashCode() {
		return 0;
	}
}
