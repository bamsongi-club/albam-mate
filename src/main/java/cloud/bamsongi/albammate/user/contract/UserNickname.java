package cloud.bamsongi.albammate.user.contract;

import java.util.Optional;

/** 사용자 모듈이 소유하는 닉네임 정규화와 불변식이다. */
public final class UserNickname {

    private final String value;

    private UserNickname(String value) {
        this.value = value;
    }

    public static Optional<UserNickname> from(String rawNickname) {
        if (rawNickname == null) {
            return Optional.empty();
        }

        String normalizedNickname = rawNickname.strip();
        int codePointCount = normalizedNickname.codePointCount(0, normalizedNickname.length());
        if (codePointCount < 1
                || codePointCount > 50
                || normalizedNickname.codePoints().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        return Optional.of(new UserNickname(normalizedNickname));
    }

    public String value() {
        return value;
    }
}
