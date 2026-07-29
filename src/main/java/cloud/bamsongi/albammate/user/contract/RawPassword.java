package cloud.bamsongi.albammate.user.contract;

import java.util.Optional;

/** 회원가입 비밀번호 정책을 만족하는 원문 비밀번호다. */
public final class RawPassword {

    private final String value;

    private RawPassword(String value) {
        this.value = value;
    }

    public static Optional<RawPassword> from(String rawPassword) {
        if (!UserPasswordPolicy.isValidSignupPassword(rawPassword)) {
            return Optional.empty();
        }
        return Optional.of(new RawPassword(rawPassword));
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
        if (this == other) {
            return true;
        }
        if (!(other instanceof RawPassword rawPassword)) {
            return false;
        }
        return value.equals(rawPassword.value);
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
