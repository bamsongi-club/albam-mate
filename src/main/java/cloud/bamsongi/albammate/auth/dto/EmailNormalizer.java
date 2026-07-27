package cloud.bamsongi.albammate.auth.dto;

import java.util.Locale;
import java.util.Optional;

/** 회원가입과 로그인에서 함께 사용하는 이메일 정규화·형식 검증이다. */
final class EmailNormalizer {

    private EmailNormalizer() {}

    static Optional<String> normalize(String rawEmail) {
        if (rawEmail == null) {
            return Optional.empty();
        }

        String normalizedEmail = rawEmail.strip().toLowerCase(Locale.ROOT);
        if (normalizedEmail.isEmpty()
                || normalizedEmail.codePoints().anyMatch(Character::isISOControl)
                || normalizedEmail.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }

        int atIndex = normalizedEmail.indexOf('@');
        boolean validStructure =
                atIndex > 0
                        && atIndex == normalizedEmail.lastIndexOf('@')
                        && atIndex < normalizedEmail.length() - 1
                        && normalizedEmail.charAt(atIndex - 1) != '.'
                        && normalizedEmail.charAt(atIndex + 1) != '.'
                        && !normalizedEmail.substring(atIndex + 1).contains("..");
        if (!validStructure || normalizedEmail.codePointCount(0, normalizedEmail.length()) > 255) {
            return Optional.empty();
        }

        return Optional.of(normalizedEmail);
    }
}
