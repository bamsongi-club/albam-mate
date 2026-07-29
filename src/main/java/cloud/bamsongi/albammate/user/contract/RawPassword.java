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

	/**
	 * 모든 인스턴스가 같은 해시를 반환한다. 비밀번호 원문이 해시 값으로 새어나가지 않게 하려는 의도이며, 같은 값이면 해시도 같아야
	 * 한다는 {@link #equals(Object)} 계약은 그대로 지킨다. 대신 모든 인스턴스가 한 버킷에 모이므로 이 타입을
	 * {@code HashMap} 키나 {@code HashSet} 원소로 쓰지 않는다.
	 */
	@Override
	public int hashCode() {
		return 0;
	}
}
