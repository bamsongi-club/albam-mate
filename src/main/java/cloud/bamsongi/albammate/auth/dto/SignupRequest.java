package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.exception.SignupValidationException;
import cloud.bamsongi.albammate.auth.validation.ValidSignupRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 회원가입 요청 원문과 정규화된 내부 입력을 분리한다. */
@ValidSignupRequest
public record SignupRequest(String email, String password, String nickname) {

    public Normalized normalizeAndValidate() {
        if (email == null || password == null || nickname == null) {
            throw new SignupValidationException();
        }

        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        String normalizedNickname = nickname.strip();

        if (!isValidEmail(normalizedEmail)
                || normalizedEmail.codePointCount(0, normalizedEmail.length()) > 255
                || !isValidPassword(password)
                || !isValidNickname(normalizedNickname)) {
            throw new SignupValidationException();
        }

        return new Normalized(normalizedEmail, password, normalizedNickname);
    }

    private boolean isValidPassword(String rawPassword) {
        int codePointCount = rawPassword.codePointCount(0, rawPassword.length());
        return codePointCount >= 15
                && codePointCount <= 64
                && rawPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private boolean isValidNickname(String normalizedNickname) {
        int codePointCount = normalizedNickname.codePointCount(0, normalizedNickname.length());
        if (codePointCount < 1 || codePointCount > 50) {
            return false;
        }
        return normalizedNickname.codePoints().noneMatch(Character::isISOControl);
    }

    private boolean isValidEmail(String normalizedEmail) {
        if (normalizedEmail.isEmpty()
                || normalizedEmail.codePoints().anyMatch(Character::isISOControl)
                || normalizedEmail.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        int atIndex = normalizedEmail.indexOf('@');
        return atIndex > 0
                && atIndex == normalizedEmail.lastIndexOf('@')
                && atIndex < normalizedEmail.length() - 1
                && normalizedEmail.charAt(atIndex - 1) != '.'
                && normalizedEmail.charAt(atIndex + 1) != '.'
                && !normalizedEmail.substring(atIndex + 1).contains("..");
    }

    public record Normalized(String email, String password, String nickname) {}
}
